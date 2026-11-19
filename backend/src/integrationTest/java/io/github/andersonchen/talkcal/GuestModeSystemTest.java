package io.github.andersonchen.talkcal;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.auth.application.port.in.ExpireGuestsUseCase;

import io.micrometer.core.instrument.MeterRegistry;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 訪客模式要整個 app 組在一起才測得出來的地方：Spring Security 的 filter（訪客 cookie、等級）、
 * 每日次數的 interceptor 和規則表（V15）、Redis 的計數、訪客併進正式帳號時跨模組搬資料。
 *
 * 每一題用 BrowserSession.forwardedFor 扮成不同 IP 的訪客，「同 IP 共用、不同 IP 各自算」才測得出來。
 * 模型指到連不上的位址：問助理一律 502，用來驗「模型失敗不扣次數」；要測第 4 次被擋時，直接把 Redis 的計數設成 3。
 * 規則表每次請求都重讀（rules-refresh=0s），「加一列就生效」不用等。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.openai.chat.base-url=http://127.0.0.1:1/v1", "talkcal.quota.rules-refresh=0s"})
@DisplayName("系統測試：訪客模式和每日次數")
class GuestModeSystemTest {

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry registry) {
        TestPostgres.newDatabase().register(registry);
        TestRedis.register(registry);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENTS = "/api/calendar/events";
    private static final String JANUARY = EVENTS + "?from=2030-01-01&to=2030-02-01";
    private static final String GUEST_IP = "203.0.113.7";
    private static final String OTHER_IP = "198.51.100.4";

    @LocalServerPort
    int port;

    @Value("${calendar.zone}")
    ZoneId zone;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ExpireGuestsUseCase expireGuests;

    @BeforeEach
    void clean() {
        for (String table : new String[] {"calendar_event", "calendar_assistant_message", "assistant_proposal",
                "calendar_add_request", "guest_token", "account"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        redis.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private BrowserSession browserFrom(String ip) {
        return new BrowserSession(port).forwardedFor(ip);
    }

    private static JsonNode startGuest(BrowserSession browser) throws Exception {
        HttpResponse<String> started = browser.postJson("/api/auth/guest", "{}");
        assertEquals(201, started.statusCode(), started.body());
        return JSON.readTree(started.body());
    }

    private static HttpResponse<String> addEvent(BrowserSession who, String title) throws Exception {
        return who.postJson(EVENTS, """
                {"events": [{"title": "%s", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"}]}""".formatted(title));
    }

    private static HttpResponse<String> ask(BrowserSession who, String message) throws Exception {
        return who.postJson("/api/calendar/assistant", JSON.writeValueAsString(Map.of("message", message)));
    }

    private static int eventsIn(BrowserSession who) throws Exception {
        HttpResponse<String> listed = who.get(JANUARY);
        assertEquals(200, listed.statusCode(), listed.body());
        return JSON.readTree(listed.body()).at("/events").size();
    }

    private String key(String bucket, String subject) {
        return "quota:" + bucket + ":" + subject + ":" + LocalDate.now(zone);
    }

    private void used(String bucket, String subject, int times) {
        redis.opsForValue().set(key(bucket, subject), Integer.toString(times));
    }

    private int counted(String bucket, String subject) {
        String value = redis.opsForValue().get(key(bucket, subject));
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static void assertQuotaExceeded(HttpResponse<String> response) throws Exception {
        assertEquals(429, response.statusCode(), response.body());
        assertEquals("QUOTA_EXCEEDED", JSON.readTree(response.body()).at("/code").asString());
        long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter > 0 && retryAfter <= 24 * 3600, "Retry-After 是離換日還有幾秒：" + retryAfter);
    }

    private BrowserSession reopened(BrowserSession browser, String ip) {
        String token = browser.cookie("talkcal_guest", "/api").orElseThrow();
        return browserFrom(ip).withCookie("talkcal_guest", token, "/api");
    }

    // ── 進入、身分 ────────────────────────────────────────────────────

    @Test
    @DisplayName("按「以訪客身分試用」開一個訪客帳號並登入：可以新增行程，問助理也會呼叫模型（模型連不上所以 502，不是 401）")
    void guestUsesTheRealFeatures() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        JsonNode me = startGuest(guest);
        assertEquals("guest", me.at("/tier").asString());

        assertEquals(201, addEvent(guest, "牙醫").statusCode());
        assertEquals(1, eventsIn(guest));
        assertEquals(502, ask(guest, "明天我有什麼行程？").statusCode());
    }

    @Test
    @DisplayName("關掉瀏覽器再開（只剩訪客 cookie）：還是同一個訪客，行程還在")
    void cookieBringsTheSameGuestBack() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        String id = startGuest(guest).at("/id").asString();
        addEvent(guest, "牙醫");

        BrowserSession again = reopened(guest, GUEST_IP);
        HttpResponse<String> me = again.get("/api/auth/me");
        assertEquals(200, me.statusCode(), me.body());
        assertEquals(id, JSON.readTree(me.body()).at("/id").asString());
        assertEquals(1, eventsIn(again));
    }

    @Test
    @DisplayName("反例：訪客 cookie 過期之後再進來，不是原本的訪客（沒登入）")
    void expiredCookieIsNotTheSameGuest() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);
        jdbc.sql("UPDATE guest_token SET expires_at = now() - interval '1 minute'").update();

        assertEquals(401, reopened(guest, GUEST_IP).get("/api/auth/me").statusCode());
    }

    @Test
    @DisplayName("反例：同一個 IP 的兩個瀏覽器是兩個訪客，看不到彼此的行程")
    void twoBrowsersAreTwoGuests() throws Exception {
        BrowserSession amy = browserFrom(GUEST_IP);
        BrowserSession bob = browserFrom(GUEST_IP);
        assertNotEquals(startGuest(amy).at("/id").asString(), startGuest(bob).at("/id").asString());

        addEvent(amy, "Amy 的牙醫");
        assertEquals(0, eventsIn(bob));
    }

    @Test
    @DisplayName("反例：沒有 session 也沒有訪客 cookie，呼叫 /api/calendar/* 回 401")
    void nobodyGetsNothing() throws Exception {
        BrowserSession stranger = browserFrom(GUEST_IP);
        assertEquals(401, stranger.get(JANUARY).statusCode());
        assertEquals(401, addEvent(stranger, "牙醫").statusCode());
        assertEquals(401, ask(stranger, "明天我有什麼行程？").statusCode());
    }

    // ── 每日次數 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("全站當天第 31 個新訪客被擋（429）；反例：用訪客 cookie 回來的訪客不占名額，照樣登入")
    void dailyGuestCap() throws Exception {
        BrowserSession returning = browserFrom(OTHER_IP);
        startGuest(returning);
        used("guest-signup", "all", 30);

        assertQuotaExceeded(browserFrom(GUEST_IP).postJson("/api/auth/guest", "{}"));
        assertEquals(200, reopened(returning, OTHER_IP).get("/api/auth/me").statusCode());
    }

    @Test
    @DisplayName("同一個 IP 每天最多開 3 個訪客帳號，第 4 個回 429；反例：別的 IP 照樣開得了")
    void guestSignupPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            startGuest(browserFrom(GUEST_IP));
        }
        assertQuotaExceeded(browserFrom(GUEST_IP).postJson("/api/auth/guest", "{}"));
        startGuest(browserFrom(OTHER_IP));
    }

    @Test
    @DisplayName("反例：送錯的請求（400）不扣增刪改的次數")
    void rejectedRequestsAreNotCounted() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);

        HttpResponse<String> invalid = guest.postJson(EVENTS, """
                {"events": [{"title": "反了", "start": "2030-01-15T11:00:00", "end": "2030-01-15T10:00:00"}]}""");
        assertEquals(400, invalid.statusCode(), invalid.body());
        // 退回在回應送出之後才執行，可能比回應晚一點
        await().atMost(Duration.ofSeconds(5)).until(() -> counted("guest-calendar-writes", GUEST_IP) == 0);
    }

    @Test
    @DisplayName("同一個 IP 第 4 次問助理回 429，沒有呼叫模型")
    void fourthQuestionIs429() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);
        used("guest-assistant", GUEST_IP, 3);

        assertQuotaExceeded(ask(guest, "明天我有什麼行程？"));
    }

    @Test
    @DisplayName("反例：模型失敗（502）的那幾次不扣次數")
    void modelFailuresAreNotCounted() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);
        for (int i = 0; i < 4; i++) {
            assertEquals(502, ask(guest, "明天我有什麼行程？").statusCode());
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> counted("guest-assistant", GUEST_IP) == 0);
    }

    @Test
    @DisplayName("增刪改行程同一個 IP 合計 10 次，第 11 次回 429；一次加 3 筆只算 1 次")
    void calendarWritesShareTenPerDay() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);

        HttpResponse<String> batch = guest.postJson(EVENTS, """
                {"events": [
                  {"title": "一", "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00"},
                  {"title": "二", "start": "2030-01-16T10:00:00", "end": "2030-01-16T11:00:00"},
                  {"title": "三", "start": "2030-01-17T10:00:00", "end": "2030-01-17T11:00:00"}]}""");
        assertEquals(201, batch.statusCode(), batch.body());
        assertEquals(1, counted("guest-calendar-writes", GUEST_IP));

        String id = JSON.readTree(batch.body()).at("/events/0/id").asString();
        assertEquals(204, guest.delete(EVENTS + "/" + id).statusCode());
        for (int i = 0; i < 8; i++) {
            assertEquals(201, addEvent(guest, "第 " + i + " 筆").statusCode());
        }
        assertEquals(10, counted("guest-calendar-writes", GUEST_IP));

        assertQuotaExceeded(addEvent(guest, "第 11 次"));
    }

    @Test
    @DisplayName("增刪改的次數用完時，按助理卡片的確認（帶 proposalId 的新增）也回 429，行程不變")
    void cardConfirmBlockedWhenWritesUsedUp() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);
        used("guest-calendar-writes", GUEST_IP, 10);

        HttpResponse<String> confirmed = guest.postJson(EVENTS, """
                {"events": [{"title": "和 Amy 開會", "start": "2030-01-15T15:00:00", "end": "2030-01-15T16:00:00",
                  "proposalId": "%s"}]}""".formatted(UUID.randomUUID()));
        assertQuotaExceeded(confirmed);
        assertEquals(0, eventsIn(guest));
    }

    @Test
    @DisplayName("反例：同一個 IP 的兩個訪客共用次數；不同 IP 各自計數")
    void countedPerIp() throws Exception {
        BrowserSession amy = browserFrom(GUEST_IP);
        BrowserSession bob = browserFrom(GUEST_IP);
        BrowserSession cat = browserFrom(OTHER_IP);
        startGuest(amy);
        startGuest(bob);
        startGuest(cat);
        used("guest-calendar-writes", GUEST_IP, 10);

        assertQuotaExceeded(addEvent(bob, "牙醫"));
        assertEquals(201, addEvent(cat, "牙醫").statusCode());
    }

    @Test
    @DisplayName("規則表加一列就能限制一個新的 API：訪客讀月曆每天 1 次，第 2 次回 429，不用改程式")
    void newRuleLimitsNewApi() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);
        assertEquals(200, guest.get(JANUARY).statusCode());

        jdbc.sql("INSERT INTO quota_bucket (name, subject, daily_limit, message) VALUES ('test-reads', 'account', 1, '今天看夠了')").update();
        jdbc.sql("INSERT INTO quota_rule (method, path_pattern, tier, bucket) VALUES ('GET', '/api/calendar/events', 'guest', 'test-reads')").update();
        try {
            assertEquals(200, guest.get(JANUARY).statusCode());
            assertQuotaExceeded(guest.get(JANUARY));
        } finally {
            jdbc.sql("DELETE FROM quota_rule WHERE bucket = 'test-reads'").update();
            jdbc.sql("DELETE FROM quota_bucket WHERE name = 'test-reads'").update();
        }
    }

    @Test
    @DisplayName("反例：會員不受訪客的規則限制")
    void membersAreNotLimitedByGuestRules() throws Exception {
        BrowserSession amy = browserFrom(GUEST_IP).signUpAndLogIn("amy", "correct-horse");
        used("guest-calendar-writes", GUEST_IP, 10);

        assertEquals(201, addEvent(amy, "牙醫").statusCode());
    }

    // ── 轉成正式會員、清理 ────────────────────────────────────────────

    @Test
    @DisplayName("訪客註冊：帳號 ID 不變，原本的行程都在，等級變成免費")
    void registerUpgradesTheSameAccount() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        String id = startGuest(guest).at("/id").asString();
        addEvent(guest, "牙醫");

        HttpResponse<String> registered = guest.register("amy", "correct-horse");
        assertEquals(201, registered.statusCode(), registered.body());
        assertEquals(id, JSON.readTree(registered.body()).at("/id").asString());
        assertEquals(204, guest.login("amy", "correct-horse").statusCode());

        JsonNode me = JSON.readTree(guest.get("/api/auth/me").body());
        assertEquals(id, me.at("/id").asString());
        assertEquals("free", me.at("/tier").asString());
        assertEquals(1, eventsIn(guest));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM guest_token").query(Integer.class).single());
    }

    @Test
    @DisplayName("訪客登入已有的帳號：訪客的行程併進那個帳號，訪客帳號被刪掉")
    void loginMergesTheGuest() throws Exception {
        BrowserSession member = browserFrom(OTHER_IP).signUpAndLogIn("amy", "correct-horse");
        addEvent(member, "原本的會議");

        BrowserSession guest = browserFrom(GUEST_IP);
        String guestId = startGuest(guest).at("/id").asString();
        addEvent(guest, "訪客時加的牙醫");

        assertEquals(204, guest.login("amy", "correct-horse").statusCode());
        assertEquals(2, eventsIn(guest));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM account WHERE id = :id").param("id", UUID.fromString(guestId))
                .query(Integer.class).single());
    }

    @Test
    @DisplayName("超過期限沒用的訪客帳號連同行程一起刪掉；反例：最近用過的訪客、會員都還在")
    void inactiveGuestsAreDeleted() throws Exception {
        BrowserSession old = browserFrom(GUEST_IP);
        String oldId = startGuest(old).at("/id").asString();
        addEvent(old, "很久以前的行程");
        BrowserSession recent = browserFrom(OTHER_IP);
        startGuest(recent);
        BrowserSession member = browserFrom(OTHER_IP).signUpAndLogIn("amy", "correct-horse");
        addEvent(member, "會員的行程");
        jdbc.sql("UPDATE account SET last_active_at = now() - interval '8 days' WHERE id = :id")
                .param("id", UUID.fromString(oldId)).update();

        assertEquals(1, expireGuests.expireInactiveSince(Instant.now().minus(Duration.ofDays(7))));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM calendar_event WHERE owner_id = :id")
                .param("id", UUID.fromString(oldId)).query(Integer.class).single());
        assertEquals(200, recent.get("/api/auth/me").statusCode());
        assertEquals(1, eventsIn(member));
    }

    // ── 觀測 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("http.server.requests 帶 tier：訪客是 guest、會員是 free；反例：沒登入的是 none，每條都有 tier")
    void requestsAreTaggedWithTier() throws Exception {
        BrowserSession guest = browserFrom(GUEST_IP);
        startGuest(guest);
        guest.get(JANUARY);
        browserFrom(OTHER_IP).signUpAndLogIn("amy", "correct-horse").get(JANUARY);
        browserFrom(OTHER_IP).get(JANUARY);

        // 觀測在回應送出之後才結束，指標可能比回應晚一點記上
        for (String tier : new String[] {"guest", "free", "none"}) {
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> meters.find("http.server.requests").tag("tier", tier).timer() != null);
        }
        assertNotNull(meters.find("http.server.requests").tag("tier", "guest").tag("uri", EVENTS).timer());
        assertNull(meters.find("http.server.requests").timers().stream()
                .filter(t -> t.getId().getTag("tier") == null).findFirst().orElse(null));
    }
}
