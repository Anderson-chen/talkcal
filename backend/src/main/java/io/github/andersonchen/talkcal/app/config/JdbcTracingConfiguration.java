package io.github.andersonchen.talkcal.app.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * datasource-micrometer 替每次拿連線、每條 SQL 開一個 Observation（application.properties 的 jdbc.*）。
 * 這裡把沒有上層 Observation（不在任何請求、排程裡）的那些丟掉：
 * - management 埠上的請求（健康檢查每 10 秒一次、Alloy 抓指標）不觀測，裡面碰到的 SQL 就各自成了一個 trace
 * - 啟動時的 Flyway，每條 SQL 一個 trace
 * 留著的話，Tempo 上真正的請求會混在大量只有一個 span 的 trace 裡，service map 上 talkcal → postgresql 的流量也會虛增。
 * 排程裡的 SQL 照樣留著：Spring 替 @Scheduled 開了 Observation。
 */
@Configuration(proxyBeanMethods = false)
class JdbcTracingConfiguration {

    // datasource-micrometer 的 Observation 名稱都以 jdbc. 開頭
    @Bean
    ObservationPredicate skipJdbcObservationsWithoutParent() {
        return (name, context) -> !name.startsWith("jdbc.") || context.getParentObservation() != null;
    }
}
