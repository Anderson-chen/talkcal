package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;

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

import tools.jackson.databind.ObjectMapper;

/**
 * 認證接起來的樣子（#103）：真的 SecurityFilterChain、真的 session cookie、真的 CSRF、真的 account 表。
 *
 * 每條規則都配一個反例：證明該擋的會擋，也證明正常的請求沒被誤擋。
 * 用 BrowserSession 照前端的方式送請求（記 cookie、從 XSRF-TOKEN 讀 token 放進 header），測的就是前端會遇到的流程。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("系統測試：認證")
class AuthSystemTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "correct-horse";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM account").update();
    }

    private BrowserSession browser() {
        return new BrowserSession(port);
    }

    @Test
    @DisplayName("沒登入：行事曆的 API 回 401 JSON，不是轉址到登入頁")
    void anonymousIsRejected() throws Exception {
        HttpResponse<String> response = browser().get("/api/calendar/events?from=2030-01-01&to=2030-02-01");

        assertEquals(401, response.statusCode());
        assertEquals("請先登入", JSON.readTree(response.body()).at("/error").asString());
    }

    @Test
    @DisplayName("註冊 → 登入 → 用得了行事曆、/me 是自己 → 登出 → 又是 401")
    void fullCycle() throws Exception {
        BrowserSession amy = browser();
        HttpResponse<String> registered = amy.register("  Amy ", PASSWORD);
        assertEquals(201, registered.statusCode(), registered.body());
        assertEquals("amy", JSON.readTree(registered.body()).at("/username").asString(), "帳號名稱轉成小寫存");

        // 註冊不會自動登入
        assertEquals(401, amy.get("/api/auth/me").statusCode());

        assertEquals(204, amy.login("AMY", PASSWORD).statusCode(), "登入時大小寫不分");
        HttpResponse<String> me = amy.get("/api/auth/me");
        assertEquals(200, me.statusCode());
        assertEquals("amy", JSON.readTree(me.body()).at("/username").asString());
        assertEquals(200, amy.get("/api/calendar/events?from=2030-01-01&to=2030-02-01").statusCode());

        assertEquals(204, amy.logout().statusCode());
        assertEquals(401, amy.get("/api/calendar/events?from=2030-01-01&to=2030-02-01").statusCode());
    }

    @Test
    @DisplayName("CSRF：已登入但沒帶 X-XSRF-TOKEN 的 POST 是 403；反例：同一個人帶了就 201")
    void csrfIsRequired() throws Exception {
        String event = """
                {"events": [{"title": "牙醫回診", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"}]}""";
        BrowserSession amy = browser().signUpAndLogIn("amy", PASSWORD);

        // 模擬別的網站騙 amy 的瀏覽器送請求：session cookie 照樣帶上，但讀不到 XSRF-TOKEN、送不出 header
        BrowserSession forged = amy.withoutCsrfHeader();
        HttpResponse<String> rejected = forged.postJson("/api/calendar/events", event);
        assertEquals(403, rejected.statusCode(), rejected.body());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM calendar_event").query(Integer.class).single());

        BrowserSession honest = browser().signUpAndLogIn("bob", PASSWORD);
        assertEquals(201, honest.postJson("/api/calendar/events", event).statusCode());
    }

    @Test
    @DisplayName("密碼錯、沒有這個帳號：都是 401「帳號或密碼錯誤」，分不出是哪一個（不透露帳號存不存在）")
    void wrongCredentials() throws Exception {
        browser().register("amy", PASSWORD);

        HttpResponse<String> wrongPassword = browser().login("amy", "wrong-password");
        HttpResponse<String> noSuchUser = browser().login("nobody", PASSWORD);

        assertEquals(401, wrongPassword.statusCode());
        assertEquals(401, noSuchUser.statusCode());
        assertEquals(wrongPassword.body(), noSuchUser.body());
        assertEquals("帳號或密碼錯誤", JSON.readTree(wrongPassword.body()).at("/error").asString());
    }

    @Test
    @DisplayName("註冊：撞名 409、密碼太短 400；資料庫裡存的是雜湊，不是密碼本身")
    void registrationRules() throws Exception {
        assertEquals(201, browser().register("amy", PASSWORD).statusCode());
        assertEquals(409, browser().register("AMY", "another-pass").statusCode(), "大小寫不同也算撞名");
        assertEquals(400, browser().register("carol", "short").statusCode());

        String stored = jdbc.sql("SELECT password_hash FROM account WHERE username = 'amy'").query(String.class).single();
        assertTrue(stored.startsWith("{bcrypt}"), stored);
        assertTrue(!stored.contains(PASSWORD), stored);
    }

    @Test
    @DisplayName("登入前後 session id 不一樣（防 session fixation：攻擊者先塞給你一個 session id，等你登入後拿它冒充你）")
    void sessionIdChangesOnLogin() throws Exception {
        BrowserSession amy = browser();
        amy.register("amy", PASSWORD);
        // 登入前先拿到一個 session（CSRF token 會存在 session 裡，所以 GET 一次就有）
        amy.get("/api/auth/me");
        String before = amy.cookie("JSESSIONID").orElse("（登入前沒有 session）");

        amy.login("amy", PASSWORD);

        assertNotEquals(before, amy.cookie("JSESSIONID").orElseThrow());
    }
}
