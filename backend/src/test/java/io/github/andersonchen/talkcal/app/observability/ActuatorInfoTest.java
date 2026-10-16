package io.github.andersonchen.talkcal.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * /actuator/info 回報這一版是哪個版本、哪個 commit（#25），而且只回報這些。
 *
 * 版本號本身是 build 時從外面傳進來的（cd/version.sh → compose → Dockerfile → -PappVersion），
 * 測試是 ./gradlew test 直接跑、沒有傳，所以這裡看到的是預設值 local / unknown ——
 * 正好驗「沒走部署流程時，誠實地說不知道」。有傳時帶不帶得進去，是 docker build 之後 curl 驗的（見 docs/release.md）。
 *
 * 設定跟 AccessLogTraceIdTest 一樣（RANDOM_PORT、關 Flyway），兩個測試共用同一個 Spring 容器，不多起一次。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=false")
@DisplayName("/actuator/info 回報版本")
class ActuatorInfoTest {

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    @DisplayName("沒傳版本時回報 local / unknown，不是空的、也不是假的版本號")
    void reportsDefaultVersionWhenNotBuiltByDeployment() throws Exception {
        HttpResponse<String> response = get("/actuator/info");
        assertEquals(200, response.statusCode());

        JsonNode build = JsonMapper.builder().build().readTree(response.body()).path("build");
        assertEquals("local", build.path("version").asString());
        assertEquals("unknown", build.path("commit").asString());
    }

    @Test
    @DisplayName("info 只有 build 這一段：env、java、os、process 這些主機細節不露出來")
    void exposesOnlyBuildSection() throws Exception {
        JsonNode info = JsonMapper.builder().build().readTree(get("/actuator/info").body());
        assertEquals(List.of("build"), List.copyOf(info.propertyNames()));
    }

    @Test
    @DisplayName("打開 info 沒有順便打開其他端點：/actuator/env 拿不到")
    void otherEndpointsStayHidden() throws Exception {
        assertNotEquals(200, get("/actuator/env").statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
