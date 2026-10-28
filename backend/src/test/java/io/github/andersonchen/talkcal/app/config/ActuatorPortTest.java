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
 * actuator 只在 management 埠上（#33）：API 埠打不到，management 埠打得到。
 *
 * 為什麼起真的 server：要驗的是「哪個埠回什麼」，MockMvc 沒有埠，驗不到。
 * RANDOM_PORT 時 Spring Boot 會把 management 埠也換成隨機的（設定檔寫死 8091 也一樣），
 * 跟其他測試、本機開著的 bootRun 都不會撞。
 * 不需要模型：只打 actuator 和一個沒登入就會回 401 的 API。
 * 資料庫故意指到連不到的地方（埠 1 沒人在聽），順便驗 liveness、readiness 不看資料庫；
 * 連線池要等第一次查詢才真的連，只有 Flyway 會在啟動時連，所以把它關掉。
 * properties 跟 AccessLogTraceIdTest 一字不差：Spring 才會讓兩個測試共用同一個 context，不必多起一次。
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
    // 401 而不是 200：LocalAuthConfiguration 只放行 management 埠上的 actuator（EndpointRequest），
    // API 埠上的 /actuator 跟其他沒登入的請求一樣被擋。同一個埠的話這裡會是 200（或資料庫連不到的 503）
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
        // 跟上面那個測試對照：同一個時間點，全部加總是 DOWN、liveness 是 UP。
        // 如果 liveness 也看資料庫，資料庫一掛，k8s 就會把每個副本一直重啟
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
        // 401：EndpointRequest 只放行有開放的端點，env 沒開，就跟其他路徑一樣要登入（登入了也只是 404）
        assertEquals(401, get(managementPort, "/actuator/env").statusCode());
    }

    @Test
    @DisplayName("management 埠上的請求不進 access log、也不產生 http.server.requests 指標")
    void managementPortIsNotObserved() throws Exception {
        // AccessLogFilter、Boot 開 trace 和記指標的 filter 都只裝在 API 埠那邊，management 埠沒有。
        // 所以 Alloy 每 15 秒的抓取、Docker 每 10 秒的健康檢查不必在程式裡另外排除，也淹不掉真正的請求
        Logger accessLogger = (Logger) LoggerFactory.getLogger("access");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        accessLogger.addAppender(captured);
        try {
            get(managementPort, "/actuator/health/readiness");
            get(managementPort, "/actuator/prometheus");
            // 對照組：API 埠的請求要被記到，證明上面沒記到不是因為根本沒在收
            get(apiPort, "/api/calendar/events");

            // AccessLogFilter 先把回應送出、再記 log，客戶端收到回應時那筆可能還沒記
            await().atMost(Duration.ofSeconds(5)).until(() -> !captured.list.isEmpty());
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
