package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

/**
 * 隱私（#52）接起來的樣子：使用者的話不進 log，要求刪除時刪得乾淨、刪不到別人。
 *
 * 真的 SecurityFilterChain、真的 session、真的 PostgreSQL（Flyway 跑完所有 migration）。
 * 每一題都有反例：刪 amy 時 bob 的東西都還在；log 裡沒有個資，但路徑、狀態碼照樣在。
 *
 * 不需要模型：對話直接寫進資料庫（跟 AuthorizationSystemTest 一樣），只測刪除和 log。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("系統測試：隱私（log 沒有個資、刪除帳號刪得乾淨）")
class PrivacySystemTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse";
    private static final String JANUARY = "/api/calendar/events?from=2030-01-01&to=2030-02-01";
    // 假個資：名字、電話、email 都是編的
    private static final String SECRET_TITLE = "陪王小明看身心科 0912-345-678";
    private static final String SECRET_NOTE = "聯絡 wang.xiaoming@example.com";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Environment environment;

    private BrowserSession amy;
    private BrowserSession bob;

    @BeforeEach
    void twoPeopleWithData() throws Exception {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
        jdbc.sql("DELETE FROM account").update();
        amy = new BrowserSession(port).signUpAndLogIn("amy", PASSWORD);
        bob = new BrowserSession(port).signUpAndLogIn("bob", PASSWORD);
        for (BrowserSession who : List.of(amy, bob)) {
            addSecretEvent(who);
            UUID owner = idOf(who);
            // 對話：不叫模型，直接寫進去（跟 JdbcAssistantMemoryRepository 存的形狀一樣）
            jdbc.sql("""
                            INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content)
                            VALUES (:owner, :id, 0, 'USER', :said), (:owner, :id, 1, 'ASSISTANT', '早上還是下午？')
                            """)
                    .param("owner", owner)
                    .param("id", UUID.randomUUID())
                    .param("said", "明天" + SECRET_TITLE)
                    .update();
            // 綁一個 Google 登入（跟第一次用 Google 登入時存的一樣）
            jdbc.sql("INSERT INTO account_identity (provider, subject, account_id) VALUES ('google', :subject, :owner)")
                    .param("subject", "google-" + owner)
                    .param("owner", owner)
                    .update();
        }
    }

    private static void addSecretEvent(BrowserSession who) throws Exception {
        HttpResponse<String> created = who.postJson("/api/calendar/events", """
                {"events": [{"title": "%s", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00",
                             "note": "%s"}]}""".formatted(SECRET_TITLE, SECRET_NOTE));
        assertEquals(201, created.statusCode(), created.body());
    }

    private static UUID idOf(BrowserSession who) throws Exception {
        return UUID.fromString(JSON.readTree(who.get("/api/auth/me").body()).at("/id").asString());
    }

    /** 資料庫裡這個人還剩幾列：帳號、登入方式，加上每一張帶 owner_id 欄位的表。 */
    private int rowsOf(UUID person) {
        int rows = jdbc.sql("SELECT count(*) FROM account WHERE id = :id").param("id", person).query(Integer.class).single()
                + jdbc.sql("SELECT count(*) FROM account_identity WHERE account_id = :id").param("id", person).query(Integer.class).single();
        for (String table : tablesWithOwner()) {
            rows += jdbc.sql("SELECT count(*) FROM " + table + " WHERE owner_id = :id").param("id", person).query(Integer.class).single();
        }
        return rows;
    }

    /**
     * 資料庫裡所有帶 owner_id 欄位的表 —— 不是手寫的清單：之後新增一張帶擁有者的表（冪等 key、歸檔…），
     * 這裡自動掃到；刪除流程沒跟著加，下面的「刪乾淨」就會紅燈。
     */
    private List<String> tablesWithOwner() {
        return jdbc.sql("""
                        SELECT table_name FROM information_schema.columns
                        WHERE table_schema = current_schema() AND column_name = 'owner_id'
                        ORDER BY table_name
                        """)
                .query(String.class).list();
    }

    @Test
    @DisplayName("刪除帳號：amy 的帳號、Google 登入、行程、對話、每一張帶 owner_id 的表都清空；反例：bob 的一筆都沒少")
    void deletingAccountErasesEverythingOfThatPersonOnly() throws Exception {
        UUID amyId = idOf(amy);
        UUID bobId = idOf(bob);
        int bobBefore = rowsOf(bobId);
        assertTrue(tablesWithOwner().containsAll(List.of("calendar_event", "calendar_assistant_message")), tablesWithOwner().toString());
        assertEquals(bobBefore, rowsOf(amyId), "前提：兩個人的資料一樣多");
        assertTrue(bobBefore >= 5, "前提：帳號、登入方式、行程、兩則對話都在：" + bobBefore);

        HttpResponse<String> deleted = amy.delete("/api/auth/me");

        assertEquals(204, deleted.statusCode(), deleted.body());
        assertEquals(0, rowsOf(amyId));
        assertEquals(bobBefore, rowsOf(bobId));
        assertEquals(1, JSON.readTree(bob.get(JANUARY).body()).at("/events").size());
    }

    @Test
    @DisplayName("刪完就登出了：session 失效（401），原本的帳密也登不進來；反例：bob 照常")
    void deletedAccountIsSignedOutAndCannotSignInAgain() throws Exception {
        amy.delete("/api/auth/me");

        assertEquals(401, amy.get("/api/auth/me").statusCode());
        assertEquals(401, amy.get(JANUARY).statusCode());
        assertEquals(401, new BrowserSession(port).login("amy", PASSWORD).statusCode());
        assertEquals(200, bob.get("/api/auth/me").statusCode());
    }

    @Test
    @DisplayName("沒帶 CSRF token（別的網站騙瀏覽器送的）：403，什麼都沒刪；沒登入：401")
    void cannotBeTriggeredCrossSiteOrAnonymously() throws Exception {
        UUID amyId = idOf(amy);
        int before = rowsOf(amyId);
        // amy 的 cookie 照樣會帶上（瀏覽器自動帶），但別的網站讀不到 XSRF-TOKEN、送不出 header
        BrowserSession forged = amy.withoutCsrfHeader();

        assertEquals(403, forged.delete("/api/auth/me").statusCode());
        assertEquals(401, new BrowserSession(port).delete("/api/auth/me").statusCode());
        assertEquals(before, rowsOf(amyId));
    }

    @Test
    @DisplayName("log 裡沒有使用者送的內容（行程標題、備註的名字、電話、email）；反例：方法、路徑、狀態碼、traceId 照樣在")
    void logsCarryNoPersonalData(CapturedOutput output) throws Exception {
        addSecretEvent(amy);
        amy.get(JANUARY);

        String logs = output.getOut();
        for (String secret : List.of("王小明", "0912-345-678", "wang.xiaoming@example.com", "身心科")) {
            assertFalse(logs.contains(secret), "log 裡出現了「" + secret + "」：\n" + logs);
        }
        assertTrue(logs.contains("POST /api/calendar/events -> 201"), logs);
        // 回應是 JSON 的行程列表，裡面有標題 —— 只能出現大小
        assertTrue(logs.contains("GET /api/calendar/events -> 200"), logs);
    }

    @Test
    @DisplayName("Spring AI 的 prompt、回覆、工具內容都不進觀測（application.properties 明寫 false，被改掉就紅燈）")
    void springAiContentObservationsAreOff() {
        for (String key : List.of("spring.ai.chat.observations.log-prompt", "spring.ai.chat.observations.log-completion",
                "spring.ai.tools.observations.include-content")) {
            assertEquals(Boolean.FALSE, environment.getProperty(key, Boolean.class), key);
        }
    }
}
