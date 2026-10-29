package io.github.andersonchen.talkcal.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.andersonchen.talkcal.calendar.adapter.shared.ModelFailurePredicate;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * application.properties 的 resilience4j.* 真的被讀進去了（#44）。
 *
 * 為什麼要測：starter 對設定很寬容 —— 名字打錯、key 拼錯、沒寫在設定裡的依賴，都會靜靜用 Resilience4j 內建的預設建一個，
 * 不報錯。那樣斷路器照樣「有」，只是門檻全錯（內建是 100 次的視窗、60 秒才算慢、bulkhead 25 個），
 * 從外面看不出來。所以拿 app 實際建出來的那一組，逐項對設定檔寫的值。
 *
 * 起整個容器、不連資料庫，理由同 OpenApiDocumentationTest。
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
// 用不到 MockMvc，加上它只為了跟 OpenApiDocumentationTest 的設定一模一樣：Spring 測試會重用同一個容器，不必再起一次
@AutoConfigureMockMvc
@DisplayName("斷路器、bulkhead 的設定")
class ResilienceSettingsTest {

    @Autowired
    CircuitBreakerRegistry breakers;

    @Autowired
    BulkheadRegistry bulkheads;

    @Autowired
    MeterRegistry meters;

    @Autowired
    HealthEndpoint health;

    @Test
    @DisplayName("模型的斷路器：共用預設 + 自己的慢呼叫門檻、失敗判斷，都是設定檔寫的值")
    void chatModelCircuitBreaker() {
        CircuitBreakerConfig config = breakers.circuitBreaker(ResilienceConfiguration.CHAT_MODEL).getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallRateThreshold()).isEqualTo(50);
        assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(30).toMillis());
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
        assertThat(config.getRecordExceptionPredicate()).isInstanceOf(ModelFailurePredicate.class);
    }

    @Test
    @DisplayName("模型的 bulkhead：50 個，滿了不排隊")
    void chatModelBulkhead() {
        BulkheadConfig config = bulkheads.bulkhead(ResilienceConfiguration.CHAT_MODEL).getBulkheadConfig();

        assertThat(config.getMaxConcurrentCalls()).isEqualTo(50);
        assertThat(config.getMaxWaitDuration()).isZero();
    }

    @Test
    @DisplayName("反例：沒寫在設定裡的依賴拿到的是共用預設，不是 Resilience4j 內建的值，也沒有模型專屬的設定")
    void unknownDependencyGetsSharedDefaults() {
        CircuitBreakerConfig config = breakers.circuitBreaker("not-configured").getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).as("共用預設，不是內建的 100").isEqualTo(10);
        assertThat(config.getSlowCallDurationThreshold()).as("模型專屬的 30 秒不會套到別人身上").isNotEqualTo(Duration.ofSeconds(30));
        assertThat(config.getRecordExceptionPredicate()).isNotInstanceOf(ModelFailurePredicate.class);
    }

    @Test
    @DisplayName("指標：斷路器狀態、bulkhead 剩幾個位子、擋掉幾個，都帶著 name=chat-model 出現在 /actuator/prometheus 那份")
    void metricsAreRegistered() {
        // 先取一次，確保已經建好（registry 建了才會接上指標）
        breakers.circuitBreaker(ResilienceConfiguration.CHAT_MODEL);
        bulkheads.bulkhead(ResilienceConfiguration.CHAT_MODEL);

        assertThat(meters.find("resilience4j.circuitbreaker.state").tag("name", "chat-model").gauges()).isNotEmpty();
        assertThat(meters.find("resilience4j.bulkhead.available.concurrent.calls").tag("name", "chat-model").gauge()).isNotNull();
        assertThat(meters.find("talkcal.bulkhead.rejected").tag("name", "chat-model").counter()).isNotNull();
    }

    /**
     * starter 可以把斷路器狀態接進 /actuator/health，打開時 health 變 DOWN。那樣模型一掛，Docker 的 healthcheck 就判 app 不健康、
     * 雲端的負載平衡器把它拿掉 —— 行程 CRUD 跟著不能用，正好是斷路器要防的事。
     * 所以 health 裡不能有斷路器（starter 預設也是關的，這裡守著它）。
     * 不看整體的 UP／DOWN：這個測試沒有資料庫，health 裡的資料庫檢查本來就是 DOWN（而且問一次要等連線逾時）。
     */
    @Test
    @DisplayName("反例：模型的斷路器打開時，health 裡也沒有斷路器這一項（模型掛了不等於 app 掛了）")
    void openBreakerIsNotPartOfHealth() {
        CircuitBreaker breaker = breakers.circuitBreaker(ResilienceConfiguration.CHAT_MODEL);
        breaker.transitionToForcedOpenState();
        try {
            assertThat(health.healthForPath("circuitBreakers")).isNull();
        } finally {
            breaker.reset();
        }
    }
}
