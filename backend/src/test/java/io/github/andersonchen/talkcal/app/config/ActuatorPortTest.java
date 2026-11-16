package io.github.andersonchen.talkcal.app.config;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 啟動真的 server：要驗的是哪個埠回什麼，MockMvc 沒有埠。RANDOM_PORT 時 management 埠也是隨機的，
 * 設定檔寫的 8091 不會跟本機執行中的 bootRun 衝突。
 *
 * 資料庫指到沒人在聽的埠 1，用來驗 liveness、readiness 不看資料庫。啟動時只有 Flyway 會連資料庫，所以關掉 Flyway。
 * properties 要跟 AccessLogTraceIdTest 一字不差，兩個測試才會共用同一個 Spring context。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.datasource.url=jdbc:postgresql://localhost:1/talkcal"})
@DisplayName("actuator 只開在 management 埠")
class ActuatorPortTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int apiPort;

    @LocalManagementPort
    int managementPort;

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/actuator/prometheus"})
    // SecurityConfiguration 只放行 management 埠上的 actuator，API 埠上的 /actuator 跟其他沒登入的請求一樣是 401。
    // actuator 跟 API 開在同一個埠的話，這裡會是 200（或資料庫連不到的 503）
    @DisplayName("API 埠上打 actuator 拿不到東西（401）")
    void apiPortHasNoActuator(String path) throws Exception {
        assertEquals(401, get(apiPort, path).statusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    @DisplayName("management 埠上的 liveness、readiness 回 200 UP —— 資料庫連不到也一樣")
    void managementPortServesProbes(String path) throws Exception {
        HttpResponse<String> response = get(managementPort, path);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"UP\""), response.body());
    }

    @Test
    @DisplayName("management 埠上的 /actuator/health 含資料庫：連不到就是 503 DOWN")
    void managementPortHealthIncludesDatabase() throws Exception {
        // 跟 managementPortServesProbes 對照：資料庫連不到時整體是 DOWN、liveness 還是 UP。
        // liveness 也看資料庫的話，資料庫一停止回應，Kubernetes 會把每個副本一直重啟
        HttpResponse<String> response = get(managementPort, "/actuator/health");
        assertEquals(503, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"DOWN\""), response.body());
    }

    @Test
    @DisplayName("management 埠上有 Prometheus 指標，Alloy 抓得到")
    void managementPortServesPrometheus() throws Exception {
        HttpResponse<String> response = get(managementPort, "/actuator/prometheus");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("jvm_memory_used_bytes"), "看不到 JVM 指標");
    }

    @Test
    @DisplayName("沒開的端點（env 會洩漏設定值）在 management 埠上也打不到")
    void managementPortHidesUnexposedEndpoints() throws Exception {
        // 沒開放的端點不在 EndpointRequest 放行的範圍，跟其他路徑一樣要先登入
        assertEquals(401, get(managementPort, "/actuator/env").statusCode());
    }

    @Test
    @DisplayName("management 埠上的請求不進 access log、也不產生 http.server.requests 指標")
    void managementPortIsNotObserved() throws Exception {
        // access log、trace、http 指標的 filter 只裝在 API 埠。Alloy 抓指標、Docker 健康檢查的請求
        // 靠這點才不必在程式裡另外排除，也不會跟真正的請求混在一起
        Logger accessLogger = (Logger) LoggerFactory.getLogger("access");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        accessLogger.addAppender(captured);
        try {
            get(managementPort, "/actuator/health/readiness");
            get(managementPort, "/actuator/prometheus");
            // 對照組：API 埠的請求有記到，上面兩個沒記到才有意義
            get(apiPort, "/api/calendar/events");

            // AccessLogFilter 送出回應之後才記 log。要連續半秒都剛好一筆：
            // 看到第一筆就停的話，晚到的 actuator 紀錄會在斷言之後才出現
            await().atMost(Duration.ofSeconds(5)).during(Duration.ofMillis(500))
                    .until(() -> captured.list.size() == 1);
            List<String> logged = List.copyOf(captured.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, logged.size(), logged.toString());
            assertTrue(logged.getFirst().contains("/api/calendar/events"), logged.toString());

            String metrics = get(managementPort, "/actuator/prometheus").body();
            assertFalse(metrics.contains("uri=\"/actuator"), "management 埠的請求被記成指標了");
            assertTrue(metrics.contains("http_server_requests_seconds_count{"), "API 埠的請求應該有指標");
        } finally {
            accessLogger.detachAppender(captured);
            captured.stop();
        }
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
