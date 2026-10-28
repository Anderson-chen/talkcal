package io.github.andersonchen.talkcal.app.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQL 的 span 只留「某個請求或排程裡」的那些（#31）。
 *
 * datasource-micrometer 替每次拿連線、每條 SQL 開一個 Observation（設定在 application.properties 的 jdbc.*）。
 * 在請求裡，它們接在 HTTP 的 span 下面，正是要看的東西。但有些 SQL 不在任何請求裡：
 *   健康檢查 —— Docker 每 10 秒打 /actuator/health，裡面的資料庫檢查會拿一次連線。
 *               actuator 走 management 埠（8091），那個埠上的請求不觀測，所以這條連線沒有上層，自己變成一個 trace
 *   啟動時的 Flyway —— 一次十幾條 SQL，各自一個 trace
 * 一小時幾百個只有一個「connection」span 的 trace，真正的請求在 Tempo 上就被淹掉了；
 * service map 上 talkcal → postgresql 那條邊的流量也會被灌水，看不出請求實際打了資料庫幾次。
 *
 * 排程（AssistantMemoryCleanup）不受影響：Spring 替 @Scheduled 開了 Observation，它的 SQL 有上層。
 */
@Configuration(proxyBeanMethods = false)
class JdbcTracingConfiguration {

    // datasource-micrometer 的 Observation 名稱都是 jdbc. 開頭（jdbc.connection、jdbc.query、jdbc.result-set）
    @Bean
    ObservationPredicate skipJdbcObservationsWithoutParent() {
        return (name, context) -> !name.startsWith("jdbc.") || context.getParentObservation() != null;
    }
}
