package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.calendar.adapter.shared.GuardedChatModel;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 叫模型的兩道保護（#44）：circuit breaker 和 bulkhead 怎麼設、指標怎麼接。怎麼用它們見 GuardedChatModel。
 *
 * 用 Resilience4j 的核心函式庫直接 new，不用它的 Spring Boot starter（註解 + AOP）：
 * 要保護的只有一個地方（ChatModel.call），明寫一個 decorator 比在方法上掛 @CircuitBreaker 好追，
 * 也不必多一層 AOP 代理。門檻放 application.properties（talkcal.model.*），理由寫在那裡。
 *
 * 指標交給 resilience4j-micrometer：breaker 的狀態、失敗率、被擋下的次數，bulkhead 剩幾個位子，
 * 都出現在 /actuator/prometheus，Grafana 的「talkcal — Spring app」上有一排在看。
 */
@Configuration(proxyBeanMethods = false)
class ModelResilienceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModelResilienceConfiguration.class);

    // 指標上 name 這個 tag 的值：breaker 和 bulkhead 用同一個名字，查的時候一眼對得上
    static final String NAME = "chat-model";

    @Bean
    CircuitBreaker modelCircuitBreaker(MeterRegistry meters,
                                       @Value("${talkcal.model.circuit-breaker.sliding-window-size}") int slidingWindowSize,
                                       @Value("${talkcal.model.circuit-breaker.minimum-number-of-calls}") int minimumNumberOfCalls,
                                       @Value("${talkcal.model.circuit-breaker.failure-rate-threshold}") float failureRateThreshold,
                                       @Value("${talkcal.model.circuit-breaker.slow-call-duration-threshold}") Duration slowCallDuration,
                                       @Value("${talkcal.model.circuit-breaker.slow-call-rate-threshold}") float slowCallRateThreshold,
                                       @Value("${talkcal.model.circuit-breaker.wait-duration-in-open-state}") Duration openFor,
                                       @Value("${talkcal.model.circuit-breaker.permitted-calls-in-half-open-state}") int halfOpenCalls) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .failureRateThreshold(failureRateThreshold)
                .slowCallDurationThreshold(slowCallDuration)
                .slowCallRateThreshold(slowCallRateThreshold)
                .waitDurationInOpenState(openFor)
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .recordException(GuardedChatModel::countsAgainstModel)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker breaker = registry.circuitBreaker(NAME);
        // 狀態一變就留一行 log：Loki 上查得到「幾點打開、幾點關回來」，跟指標的時間軸對得起來
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("模型的熔斷狀態改變：{}", event.getStateTransition()));
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return breaker;
    }

    @Bean
    Bulkhead modelBulkhead(MeterRegistry meters,
                           @Value("${talkcal.model.bulkhead.max-concurrent-calls}") int maxConcurrentCalls) {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrentCalls)
                // 滿了不排隊，立刻拒絕：排隊的請求一樣佔著執行緒，等於沒擋
                .maxWaitDuration(Duration.ZERO)
                .build();
        BulkheadRegistry registry = BulkheadRegistry.of(config);
        Bulkhead bulkhead = registry.bulkhead(NAME);
        TaggedBulkheadMetrics.ofBulkheadRegistry(registry).bindTo(meters);
        // resilience4j-micrometer 只給「剩幾個位子」，沒有「擋掉幾個」，自己數
        Counter rejected = Counter.builder("talkcal.model.bulkhead.rejected")
                .description("同時在等模型的請求已滿，被 bulkhead 直接拒絕的次數")
                .tag("name", NAME)
                .register(meters);
        bulkhead.getEventPublisher().onCallRejected(event -> rejected.increment());
        return bulkhead;
    }
}
