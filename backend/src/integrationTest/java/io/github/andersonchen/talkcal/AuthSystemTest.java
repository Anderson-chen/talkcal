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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;

/**
 * 用 BrowserSession 照前端的方式送請求，測的是前端實際會遇到的流程。
 * 每條規則都配一個反例：該擋的會擋，正常的請求不會被擋。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("系統測試：認證")
class AuthSystemTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.newDatabase().register(registry);
    }

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
        assertEquals("amy", JSON.readTree(me.body()).at("/name").asString(), "帳密登入的顯示名稱就是帳號名稱");
        assertEquals(200, amy.get("/api/calendar/events?from=2030-01-01&to=2030-02-01").statusCode());

        assertEquals(204, amy.logout().statusCode());
        assertEquals(401, amy.get("/api/calendar/events?from=2030-01-01&to=2030-02-01").statusCode());
    }

    @Test
    @DisplayName("新註冊的是免費會員；照 README 的 SQL 改成付費後，同一個 session 的下一個請求 /me 就是付費（不用重新登入）")
    void tierFollowsDatabase() throws Exception {
        BrowserSession amy = browser().signUpAndLogIn("amy", PASSWORD);
        BrowserSession bob = browser().signUpAndLogIn("bob", PASSWORD);
        assertEquals("free", JSON.readTree(amy.get("/api/auth/me").body()).at("/tier").asString());

        jdbc.sql("UPDATE account SET tier = 'paid' WHERE username = 'amy'").update();

        assertEquals("paid", JSON.readTree(amy.get("/api/auth/me").body()).at("/tier").asString());
        assertEquals("free", JSON.readTree(bob.get("/api/auth/me").body()).at("/tier").asString(), "反例：沒改到的帳號還是免費");

        jdbc.sql("UPDATE account SET tier = 'free' WHERE username = 'amy'").update();
        assertEquals("free", JSON.readTree(amy.get("/api/auth/me").body()).at("/tier").asString(), "降級也是下一個請求就生效");
    }

    @Test
    @DisplayName("CSRF：已登入但沒帶 X-XSRF-TOKEN 的 POST 是 403；反例：同一個人帶了就 201")
    void csrfIsRequired() throws Exception {
        String event = """
                {"events": [{"title": "牙醫回診", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"}]}""";
        BrowserSession amy = browser().signUpAndLogIn("amy", PASSWORD);

        // 別的網站讓 amy 的瀏覽器送出請求：session cookie 會帶上，但讀不到 XSRF-TOKEN，送不出 header
        BrowserSession forged = amy.withoutCsrfHeader();
        HttpResponse<String> rejected = forged.postJson("/api/calendar/events", event);
        assertEquals(403, rejected.statusCode(), rejected.body());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM calendar_event").query(Integer.class).single());

        BrowserSession honest = browser().signUpAndLogIn("bob", PASSWORD);
        assertEquals(201, honest.postJson("/api/calendar/events", event).statusCode());
    }

    @Test
    @DisplayName("#259 修改（PUT）也要登入、也要 CSRF：沒登入 401、沒帶 X-XSRF-TOKEN 403，那筆沒被改；反例：本人帶了就 200")
    void updateNeedsLoginAndCsrf() throws Exception {
        String before = """
                {"title": "牙醫回診", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"}""";
        String update = """
                {"expected": %s, "event": {"title": "洗牙", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"}}"""
                .formatted(before);
        BrowserSession amy = browser().signUpAndLogIn("amy", PASSWORD);
        HttpResponse<String> saved = amy.postJson("/api/calendar/events", "{\"events\": [" + before + "]}");
        String path = "/api/calendar/events/" + JSON.readTree(saved.body()).at("/events/0/id").asString();

        assertEquals(401, browser().putJson(path, update).statusCode());
        HttpResponse<String> forged = amy.withoutCsrfHeader().putJson(path, update);
        assertEquals(403, forged.statusCode(), forged.body());
        assertEquals("牙醫回診", jdbc.sql("SELECT title FROM calendar_event").query(String.class).single());

        // withoutCsrfHeader 改到的是 amy 這個物件本身，所以換一個瀏覽器登入同一個帳號
        BrowserSession honest = browser();
        assertEquals(204, honest.login("amy", PASSWORD).statusCode());
        HttpResponse<String> updated = honest.putJson(path, update);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("洗牙", jdbc.sql("SELECT title FROM calendar_event").query(String.class).single());
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
        // CSRF token 存在 session 裡，GET 一次就有登入前的 session
        amy.get("/api/auth/me");
        String before = amy.cookie("JSESSIONID").orElse("（登入前沒有 session）");

        amy.login("amy", PASSWORD);

        assertNotEquals(before, amy.cookie("JSESSIONID").orElseThrow());
    }

    @Test
    @DisplayName("反例：沒設定 Google、GitHub 的 client id 時，登入方式是空的、OAuth2 的網址也不存在 —— 跟 #105 之前一樣只有帳密")
    void noExternalProvidersByDefault() throws Exception {
        HttpResponse<String> providers = browser().get("/api/auth/providers");
        assertEquals(200, providers.statusCode());
        assertEquals("[]", JSON.readTree(providers.body()).at("/providers").toString());

        assertEquals(401, browser().get("/api/auth/oauth2/authorization/google").statusCode());
    }
}
