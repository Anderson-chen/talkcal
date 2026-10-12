package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 整個應用接起來跑：真的 HTTP、真的 Spring 組裝、真的 PostgreSQL（啟動時 Flyway 照正式流程跑完 V1–V8），助理那一題再加上真的模型。
 *
 * 其他測試各自只接一段真的東西（controller 用 MockMvc、adapter 直接呼叫、對話測試的行事曆是假的），
 * 各段單獨都對，不代表接起來會動。這裡抓的是「接起來才壞」的那一類：
 * bean 組裝、設定檔、Flyway 跟實際 SQL 對不上、JSON 序列化、例外對到哪個狀態碼。
 *
 * 只當黑箱用：送 JSON 字串、讀 JSON 樹，不引用 controller 的 record ——
 * 那些 record 改了名字或欄位，這裡應該要紅燈，而不是跟著一起改掉。
 *
 * 兩題分工：
 * - 行程的新增、讀取、刪除：不需要模型，GitHub 上的 CI 也會跑
 * - 問助理：需要真的 llama-server，沒開就跳過（跟 CalendarAssistantConversationTest 一樣，只在本機真的跑）
 * 模型沒開也起得來：ChatModel 是第一次呼叫時才連線，啟動時不碰它。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("系統測試：整個應用接起來")
class SystemTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    // 跟 app 讀的是同一個設定（ci/run.sh 會用環境變數改成主機上的那台），確認的就是 app 等一下要連的那一台
    @Value("${spring.ai.openai.chat.base-url}")
    String modelBaseUrl;

    // 跟 app 的「現在」用同一個時區，「明天」才會是同一天
    @Value("${calendar.zone}")
    ZoneId zone;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
    }

    @Test
    @DisplayName("行程：新增 → 讀得到 → 刪掉 → 讀不到，再刪一次是 404")
    void eventLifecycle() throws Exception {
        // 固定的未來日期：這一題跟「現在幾點」無關
        HttpResponse<String> created = post("/api/calendar/events", """
                {"events": [{"title": "牙醫回診", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00", "category": "HEALTH"}]}
                """);
        assertEquals(201, created.statusCode(), created.body());
        String id = JSON.readTree(created.body()).at("/events/0/id").asString();

        JsonNode listed = JSON.readTree(get("/api/calendar/events?from=2030-01-01&to=2030-02-01").body());
        assertEquals(1, listed.at("/events").size());
        assertEquals(id, listed.at("/events/0/id").asString());
        assertEquals("牙醫回診", listed.at("/events/0/title").asString());
        assertEquals("2030-01-15T10:00:00", listed.at("/events/0/start").asString());
        assertEquals("HEALTH", listed.at("/events/0/category").asString());

        assertEquals(204, delete("/api/calendar/events/" + id).statusCode());
        assertEquals(0, JSON.readTree(get("/api/calendar/events?from=2030-01-01&to=2030-02-01").body()).at("/events").size());
        assertEquals(404, delete("/api/calendar/events/" + id).statusCode());
    }

    @Test
    @DisplayName("不合規的行程：400，而且一筆都沒存（第一筆合規也一樣）")
    void invalidEventIsRejectedWithoutSaving() throws Exception {
        HttpResponse<String> response = post("/api/calendar/events", """
                {"events": [
                  {"title": "早會", "start": "2030-01-15T09:00:00", "end": "2030-01-15T10:00:00"},
                  {"title": "倒著走", "start": "2030-01-15T12:00:00", "end": "2030-01-15T11:00:00"}
                ]}
                """);

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).has("error"), response.body());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM calendar_event").query(Integer.class).single());
    }

    @Test
    @DisplayName("問助理：它去查了資料庫裡的行程來回答，對話存進資料庫、讀得回來")
    void assistantAnswersFromTheDatabase() throws Exception {
        Assumptions.assumeTrue(modelIsUp(), () -> "llama-server 沒有在 " + modelBaseUrl + " 執行，跳過需要模型的系統測試");

        // 「明天」跟著真的時鐘走：app 的時鐘沒換掉，這裡測的就是正式的那一個。
        // 剛好在台北午夜前後跑，app 和這裡可能各自認定不同的「明天」，機率小到不值得換掉時鐘
        LocalDate tomorrow = LocalDate.now(zone).plusDays(1);
        assertEquals(201, post("/api/calendar/events", """
                {"events": [{"title": "瑜珈課", "start": "%sT18:00:00", "end": "%sT19:00:00", "category": "HEALTH"}]}
                """.formatted(tomorrow, tomorrow)).statusCode());

        String question = "明天晚上有什麼行程？";
        HttpResponse<String> answered = post("/api/calendar/assistant", """
                {"message": "%s"}
                """.formatted(question));
        assertEquals(200, answered.statusCode(), answered.body());
        JsonNode reply = JSON.readTree(answered.body());
        String conversationId = reply.at("/conversationId").asString();
        // 措辭會隨模型改變，只看關鍵字：行程的標題只存在資料庫裡，回答裡有它就代表真的查過
        assertTrue(reply.at("/reply").asString().contains("瑜珈"), reply.toString());
        assertEquals(0, reply.at("/proposals").size(), "只是問問，不該提議新增：" + reply);

        JsonNode history = JSON.readTree(get("/api/calendar/assistant/" + conversationId).body());
        assertEquals(List.of("user", "assistant"),
                history.at("/messages").valueStream().map(line -> line.at("/role").asString()).toList());
        assertEquals(question, history.at("/messages/0/text").asString());
        assertEquals(reply.at("/reply").asString(), history.at("/messages/1/text").asString());

        // 給人看的只有兩句，資料庫裡還有中間的工具呼叫和工具結果（下一輪模型要看得到自己查過）
        int stored = jdbc.sql("SELECT count(*) FROM calendar_assistant_message WHERE conversation_id = ?")
                .param(UUID.fromString(conversationId))
                .query(Integer.class).single();
        assertTrue(stored > 2, "應該存了工具呼叫和結果，實際只有 " + stored + " 筆");
    }

    private boolean modelIsUp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(modelBaseUrl.replaceFirst("/v1/?$", "") + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return send(request(path).DELETE());
    }

    // 助理那一題會等模型（thinking 加工具呼叫），給到跟 app 呼叫模型一樣的兩分鐘
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofMinutes(2));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
