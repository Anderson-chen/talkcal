package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 授權接起來的樣子（#104）：兩個人各自登入，只看得到、改得到自己的行程和對話。
 *
 * 真的 SecurityFilterChain、真的 session、真的 PostgreSQL —— 擁有者從登入者一路帶到 SQL 的 WHERE，
 * 中間任何一層漏了（controller 沒拿登入者、service 沒往下傳、SQL 少了 owner_id 條件），這裡都會紅燈。
 *
 * 主角是 IDOR（OWASP API1 Broken Object Level Authorization）：拿到別人的 id，換自己的 session 去讀、去刪。
 * 每一題都有反例：本人用同一個 id 照樣成功，證明擋下來的是「不是你的」，而不是「這個 id 壞了」。
 *
 * 不需要模型：對話那一題直接把訊息寫進資料庫，只測讀回來的部分。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("系統測試：授權（只看得到、改得到自己的）")
class AuthorizationSystemTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse";
    private static final String JANUARY = "/api/calendar/events?from=2030-01-01&to=2030-02-01";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private BrowserSession amy;
    private BrowserSession bob;

    @BeforeEach
    void twoPeople() throws Exception {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
        jdbc.sql("DELETE FROM account").update();
        amy = new BrowserSession(port).signUpAndLogIn("amy", PASSWORD);
        bob = new BrowserSession(port).signUpAndLogIn("bob", PASSWORD);
    }

    private static String addDentist(BrowserSession who) throws Exception {
        HttpResponse<String> created = who.postJson("/api/calendar/events", """
                {"events": [{"title": "牙醫回診", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"}]}""");
        assertEquals(201, created.statusCode(), created.body());
        return JSON.readTree(created.body()).at("/events/0/id").asString();
    }

    private static int eventsIn(BrowserSession who, String path) throws Exception {
        HttpResponse<String> listed = who.get(path);
        assertEquals(200, listed.statusCode(), listed.body());
        return JSON.readTree(listed.body()).at("/events").size();
    }

    @Test
    @DisplayName("月曆：同一段期間，amy 的行程 bob 看不到；反例：amy 自己看得到")
    void listShowsOnlyOwnEvents() throws Exception {
        addDentist(amy);

        assertEquals(0, eventsIn(bob, JANUARY));
        assertEquals(1, eventsIn(amy, JANUARY));
    }

    @Test
    @DisplayName("IDOR：bob 拿 amy 行程的 id 去刪 —— 404（跟不存在一樣，不透露它存在），那筆還在；反例：amy 自己刪得掉")
    void cannotDeleteSomeoneElsesEvent() throws Exception {
        String amysEvent = addDentist(amy);

        HttpResponse<String> attempt = bob.delete("/api/calendar/events/" + amysEvent);
        HttpResponse<String> missing = bob.delete("/api/calendar/events/" + UUID.randomUUID());

        assertEquals(404, attempt.statusCode(), attempt.body());
        assertEquals(404, missing.statusCode());
        assertEquals(1, eventsIn(amy, JANUARY), "amy 的行程應該還在");
        assertEquals(204, amy.delete("/api/calendar/events/" + amysEvent).statusCode());
        assertEquals(0, eventsIn(amy, JANUARY));
    }

    @Test
    @DisplayName("新增的行程記在登入者名下，請求裡沒有可以改的擁有者欄位：body 多塞一個 owner 也沒用")
    void ownerComesFromSessionNotFromRequest() throws Exception {
        String amyId = JSON.readTree(amy.get("/api/auth/me").body()).at("/id").asString();

        HttpResponse<String> created = bob.postJson("/api/calendar/events", """
                {"owner": "%s", "ownerId": "%s",
                 "events": [{"title": "冒名", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00",
                             "owner": "%s"}]}""".formatted(amyId, amyId, amyId));

        assertEquals(201, created.statusCode(), created.body());
        assertEquals(0, eventsIn(amy, JANUARY), "不該出現在 amy 的月曆上");
        assertEquals(1, eventsIn(bob, JANUARY));
    }

    @Test
    @DisplayName("AI 助理的對話：bob 拿 amy 的 conversationId 去讀，是空的；反例：amy 讀得回來")
    void cannotReadSomeoneElsesConversation() throws Exception {
        String amyId = JSON.readTree(amy.get("/api/auth/me").body()).at("/id").asString();
        String conversation = UUID.randomUUID().toString();
        // 不叫模型：直接寫一段 amy 名下的對話（跟 JdbcAssistantMemoryRepository 存的形狀一樣）
        jdbc.sql("""
                        INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content)
                        VALUES (:owner, :id, 0, 'USER', '明天幫我約牙醫'), (:owner, :id, 1, 'ASSISTANT', '早上還是下午？')
                        """)
                .param("owner", UUID.fromString(amyId))
                .param("id", UUID.fromString(conversation))
                .update();

        JsonNode bobs = JSON.readTree(bob.get("/api/calendar/assistant/" + conversation).body());
        JsonNode amys = JSON.readTree(amy.get("/api/calendar/assistant/" + conversation).body());

        assertEquals(0, bobs.at("/messages").size(), bobs.toString());
        assertEquals(2, amys.at("/messages").size(), amys.toString());
        assertEquals("明天幫我約牙醫", amys.at("/messages/0/text").asString());
    }
}
