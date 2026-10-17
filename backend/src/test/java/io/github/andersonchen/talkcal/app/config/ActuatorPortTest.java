package io.github.andersonchen.talkcal.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * actuator 只在 management 埠上（#33）：API 埠打不到，management 埠打得到。
 *
 * 為什麼起真的 server：要驗的是「哪個埠回什麼」，MockMvc 沒有埠，驗不到。
 * RANDOM_PORT 時 Spring Boot 會把 management 埠也換成隨機的（設定檔寫死 8091 也一樣），
 * 跟其他測試、本機開著的 bootRun 都不會撞；實際拿到幾號從 local.management.port 讀。
 * 不需要模型：只打 actuator 和一個沒登入就會回 401 的 API。
 * 資料庫故意指到連不到的地方（埠 1 沒人在聽），順便驗 liveness、readiness 不看資料庫；
 * 連線池要等第一次查詢才真的連，只有 Flyway 會在啟動時連，所以把它關掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.datasource.url=jdbc:postgresql://localhost:1/talkcal"})
@DisplayName("actuator 只開在 management 埠")
class ActuatorPortTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int apiPort;

    @Value("${local.management.port}")
    int managementPort;

    @Test
    @DisplayName("兩個是不同的埠：同一個埠的話，下面「API 埠打不到」就不可能成立")
    void portsDiffer() {
        assertNotEquals(apiPort, managementPort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/actuator/prometheus"})
    // 404 而不是 401：LocalAuthConfiguration 放行 /actuator/**，請求進得來，只是這個埠上沒有這些端點
    @DisplayName("API 埠上打 actuator 只拿到 404")
    void apiPortHasNoActuator(String path) throws Exception {
        assertEquals(404, get(apiPort, path).statusCode());
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
        assertEquals(404, get(managementPort, "/actuator/env").statusCode());
    }

    @Test
    @DisplayName("API 埠本身是活的：上面的 404 不是因為整個埠都沒在聽")
    void apiPortStillServesApi() throws Exception {
        // 沒登入打 /api 回 401（LocalAuthConfiguration），證明請求真的進到 app
        assertEquals(401, get(apiPort, "/api/calendar/events").statusCode());
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
