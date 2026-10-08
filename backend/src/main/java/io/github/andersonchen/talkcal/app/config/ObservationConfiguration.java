package io.github.andersonchen.talkcal.app.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * 觀測的全域規則：什麼「不」要觀測。
 *
 * 要觀測的部分不必寫程式，Spring Boot 都自動做了：
 *   進來的請求  —— Boot 自動加的 ServerHttpObservationFilter
 *   往外的呼叫  —— Spring AI 的 ChatModel 自己開的 Observation
 * 這裡只負責把不值得看的擋掉。
 *
 * 跟 CalendarConfiguration 同放在 app.config（組裝都集中在這裡），但分成兩個類別：
 * 那邊回答「calendar 模組誰接誰」，這邊回答的是整個 app 共用的維運決定，不屬於任何一個功能模組。
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    // /actuator/* 的請求不觀測：不開 trace、也不記 http.server.requests 指標。
    //
    // 為什麼：ops/ 的 Alloy 每 15 秒來抓一次 /actuator/prometheus、Docker 每幾秒打一次 /actuator/health，
    // 一小時就是幾百筆 trace，真正的請求在 Tempo 上被淹掉（跟 AccessLogFilter 不記 actuator 同一個理由）。
    // 這些請求的健康本身已經有人看著：抓不到時 Mimir 裡的 up 會變 0，健康檢查失敗時 Docker 會標 unhealthy。
    //
    // 為什麼用 ObservationPredicate 而不是只關 trace：trace 和指標都從同一個 Observation 長出來，
    // 在源頭擋掉最單純。代價是 /actuator 的 http.server.requests 也沒了 —— 儀表板本來就把它濾掉，不受影響。
    @Bean
    ObservationPredicate skipActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
