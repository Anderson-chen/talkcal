package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 其他測試各自只用一段真的元件，各段都對不代表組在一起也正常。這裡測的是整個 app 組在一起才會出錯的地方：
 * bean 組裝、設定檔、migration 跟實際 SQL 對不上、JSON 序列化、例外對到哪個狀態碼。
 *
 * 送 JSON 字串、讀 JSON 樹，不引用 controller 的 record：record 改了名字或欄位，這裡要紅燈，不能跟著一起改掉。
 *
 * 問助理那一題要真的 llama-server，沒開就跳過，所以只在本機執行；GitHub 上的 CI 只執行行程那幾題。
 * ChatModel 和歸檔的 bucket 都是第一次用到才連線，所以模型沒開、物件儲存指到連不上的位址，app 都能啟動。
 * 物件儲存連不上時只有查看封存失敗、月曆照常，也在這裡驗；真的接上儲存的情境見 ArchiveSystemTest。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "talkcal.archive.endpoint=http://127.0.0.1:1")
@DisplayName("系統測試：整個應用接起來")
class SystemTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    // 跟 app 讀同一個設定（ci/run.sh 會用環境變數指到主機上的模型），檢查的才是 app 要連的那一台
    @Value("${spring.ai.openai.chat.base-url}")
    String modelBaseUrl;

    // 跟 app 用同一個時區，兩邊的「明天」才是同一天
    @Value("${calendar.zone}")
    ZoneId zone;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() throws Exception {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
        jdbc.sql("DELETE FROM account").update();
        // /api 要登入才能用
        user = new BrowserSession(port).signUpAndLogIn("system-test", "system-test-pass");
    }

    private BrowserSession user;

    @Test
    @DisplayName("行程：新增 → 讀得到 → 刪掉 → 讀不到，再刪一次是 404")
    void eventLifecycle() throws Exception {
        // 固定的未來日期，結果不受執行時間影響
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
    @DisplayName("物件儲存連不上：查看封存回 502（訊息不帶內部位址），2 年內的行程照常讀得到")
    void archiveDownKeepsCalendarWorking() throws Exception {
        assertEquals(201, post("/api/calendar/events", """
                {"events": [{"title": "牙醫回診", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"}]}
                """).statusCode());

        HttpResponse<String> archived = get("/api/calendar/archive/events?from=2023-05-01&to=2023-05-31");
        HttpResponse<String> listed = get("/api/calendar/events?from=2030-01-01&to=2030-02-01");

        assertEquals(502, archived.statusCode(), archived.body());
        assertTrue(JSON.readTree(archived.body()).has("error"), archived.body());
        assertFalse(archived.body().contains("127.0.0.1"), archived.body());
        assertFalse(archived.body().contains("s3://"), archived.body());
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals("牙醫回診", JSON.readTree(listed.body()).at("/events/0/title").asString());
    }

    @Test
    @DisplayName("問助理：它去查了資料庫裡的行程來回答，對話存進資料庫、讀得回來")
    void assistantAnswersFromTheDatabase() throws Exception {
        Assumptions.assumeTrue(modelIsUp(), () -> "llama-server 沒有在 " + modelBaseUrl + " 執行，跳過需要模型的系統測試");

        // app 用的是正式的時鐘，這裡也照真的時間算「明天」。剛好在台北午夜前後執行的話，
        // 兩邊認定的「明天」可能不同，機率小到不值得換掉時鐘
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
        // 措辭會隨模型改變，只比對關鍵字。行程標題只存在資料庫裡，回覆有提到就表示模型真的查過
        assertTrue(reply.at("/reply").asString().contains("瑜珈"), reply.toString());
        assertEquals(0, reply.at("/proposals").size(), "只是問問，不該提議新增：" + reply);

        JsonNode history = JSON.readTree(get("/api/calendar/assistant/" + conversationId).body());
        assertEquals(List.of("user", "assistant"),
                history.at("/messages").valueStream().map(line -> line.at("/role").asString()).toList());
        assertEquals(question, history.at("/messages/0/text").asString());
        assertEquals(reply.at("/reply").asString(), history.at("/messages/1/text").asString());

        // 畫面上只有兩句，資料庫裡還有中間的工具呼叫和工具結果：下一輪模型要看得到自己查過什麼
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
        return user.postJson(path, json);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return user.get(path);
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return user.delete(path);
    }
}
