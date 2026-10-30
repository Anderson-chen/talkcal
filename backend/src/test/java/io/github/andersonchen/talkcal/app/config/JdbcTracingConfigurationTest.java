package io.github.andersonchen.talkcal.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SQL 的 Observation 要有上層才開（JdbcTracingConfiguration）。
 *
 * 用真的 ObservationRegistry 而不是直接呼叫 predicate：規則能不能成立，要看 Micrometer 在問 predicate 之前
 * 有沒有先把「目前的 Observation」設成上層。只測 lambda 的話，這件事變了測試也照樣綠。
 * 名稱照 datasource-micrometer 實際用的（jdbc.connection、jdbc.query）。
 */
@DisplayName("SQL 的 span 只留在請求裡的")
class JdbcTracingConfigurationTest {

    private final ObservationRegistry registry = ObservationRegistry.create();
    // 真的開了的 Observation 名稱（被 predicate 擋掉的是 noop，不會走到 handler）
    private final List<String> started = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry.observationConfig()
                .observationPredicate(new JdbcTracingConfiguration().skipJdbcObservationsWithoutParent())
                .observationHandler(new ObservationHandler<>() {
                    @Override
                    public void onStart(Observation.Context context) {
                        started.add(context.getName());
                    }

                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }
                });
    }

    @Test
    @DisplayName("請求裡的 SQL 會開 span")
    void jdbcInsideRequestIsObserved() {
        Observation.createNotStarted("http.server.requests", registry).observe(() -> {
            Observation.createNotStarted("jdbc.connection", registry).observe(() -> { });
            Observation.createNotStarted("jdbc.query", registry).observe(() -> { });
        });

        assertEquals(List.of("http.server.requests", "jdbc.connection", "jdbc.query"), started);
    }

    @Test
    @DisplayName("反例：沒有上層的 SQL（健康檢查、Flyway）不開 span")
    void jdbcWithoutParentIsSkipped() {
        Observation.createNotStarted("jdbc.connection", registry).observe(() -> { });
        Observation.createNotStarted("jdbc.query", registry).observe(() -> { });

        assertEquals(List.of(), started);
    }

    @Test
    @DisplayName("反例：沒有上層的其他 Observation 不受影響（請求本身就是最上層）")
    void otherObservationsWithoutParentStillObserved() {
        Observation.createNotStarted("http.server.requests", registry).observe(() -> { });

        assertEquals(List.of("http.server.requests"), started);
    }
}
