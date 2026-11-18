package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 沒登入的訪客問助理，要整個 app 組在一起才測得出來的地方：SecurityConfiguration 只開放 POST /api/calendar/assistant、其他 API 沒有跟著開放、
 * Tomcat 照 server.forward-headers-strategy 還原的 IP、V15 的計數。
 *
 * 模型指到連不上的位址：訪客的回答只要呼叫了模型就會 502，回得出 200 就是沒有呼叫。
 * 問題的文字照 web/src/calendar/useAssistant.ts 的 SUGGESTIONS 寫，不引用 GuestDemo 的常數：前端送的就是這幾個字。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.openai.chat.base-url=http://127.0.0.1:1/v1")
@DisplayName("系統測試：沒登入的訪客問助理")
class GuestDemoSystemTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.newDatabase().register(registry);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SUGGESTIONS = List.of("明天下午3點和 Amy 開會", "明天有什麼行程？", "這週哪天下午有空？");
    private static final String URL = "/api/calendar/assistant";
    private static final String GUEST_IP = "203.0.113.7";

    @LocalServerPort
    int port;

    @Value("${calendar.zone}")
    ZoneId zone;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM guest_demo_usage").update();
    }

    private BrowserSession guestFrom(String forwardedFor) {
        return new BrowserSession(port).forwardedFor(forwardedFor);
    }

    private static HttpResponse<String> ask(BrowserSession guest, String question) throws Exception {
        return guest.postJson(URL, JSON.writeValueAsString(Map.of("message", question)));
    }

    private static void useUp(BrowserSession guest) throws Exception {
        for (String question : SUGGESTIONS) {
            HttpResponse<String> answered = ask(guest, question);
            assertEquals(200, answered.statusCode(), answered.body());
        }
    }

    @Test
    @DisplayName("沒登入點 3 個固定問題都回預先寫好的答案（模型連不上也是 200），剩餘次數 2、1、0")
    void guestSeesThreeAnswers() throws Exception {
        BrowserSession guest = guestFrom(GUEST_IP);
        for (int i = 0; i < SUGGESTIONS.size(); i++) {
            HttpResponse<String> answered = ask(guest, SUGGESTIONS.get(i));
            assertEquals(200, answered.statusCode(), answered.body());
            JsonNode body = JSON.readTree(answered.body());
            assertFalse(body.at("/reply").asString().isBlank());
            assertEquals(2 - i, body.at("/guestRemaining").asInt());
            assertTrue(body.at("/conversationId").isNull(), "訪客不記對話");
        }
    }

    @Test
    @DisplayName("新增行程那題的卡片在 calendar.zone 的明天下午 3 點，不帶 proposalId（訪客的卡片不算進採納率）")
    void cardIsTomorrow() throws Exception {
        JsonNode card = JSON.readTree(ask(guestFrom(GUEST_IP), SUGGESTIONS.getFirst()).body()).at("/proposals/0");
        assertEquals(LocalDate.now(zone).plusDays(1) + "T15:00:00", card.at("/start").asString());
        assertTrue(card.at("/proposalId").isNull() || card.at("/proposalId").isMissingNode(), card.toString());
    }

    @Test
    @DisplayName("同一個 IP 第 4 次回 429、code = GUEST_LIMIT、帶 Retry-After")
    void fourthTimeIs429() throws Exception {
        BrowserSession guest = guestFrom(GUEST_IP);
        useUp(guest);

        HttpResponse<String> refused = ask(guest, SUGGESTIONS.getFirst());
        assertEquals(429, refused.statusCode(), refused.body());
        assertEquals("GUEST_LIMIT", JSON.readTree(refused.body()).at("/code").asString());
        long retryAfter = Long.parseLong(refused.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter > 0 && retryAfter <= 24 * 3600, "Retry-After 是離換日還有幾秒：" + retryAfter);
    }

    @Test
    @DisplayName("反例：不同 IP 各自算 3 次；同一個 IP 換一個瀏覽器（新的 cookie）不會多出次數")
    void eachIpCountsSeparately() throws Exception {
        useUp(guestFrom(GUEST_IP));

        assertEquals(200, ask(guestFrom("198.51.100.4"), SUGGESTIONS.getFirst()).statusCode());
        assertEquals(429, ask(guestFrom(GUEST_IP), SUGGESTIONS.getFirst()).statusCode());
    }

    @Test
    @DisplayName("IPv6 同一個 /64 裡換位址還是同一份次數；反例：別的 /64 各自算 3 次")
    void ipv6CountsPerSlash64() throws Exception {
        useUp(guestFrom("2001:db8:0:1::1"));

        assertEquals(429, ask(guestFrom("2001:db8:0:1::abcd"), SUGGESTIONS.getFirst()).statusCode());
        assertEquals(200, ask(guestFrom("2001:db8:0:2::1"), SUGGESTIONS.getFirst()).statusCode());
    }

    @Test
    @DisplayName("反例：在 X-Forwarded-For 最左邊塞假的 IP 換不到新的次數，算的是 proxy 接在最右邊的那個")
    void forgedForwardedForDoesNotResetTheCount() throws Exception {
        useUp(guestFrom(GUEST_IP));

        // nginx 用 $proxy_add_x_forwarded_for：使用者送來的值留在前面，nginx 看到的來源接在最後
        for (String forged : List.of("1.2.3.4", "10.0.0.1", "unknown")) {
            HttpResponse<String> retried = ask(guestFrom(forged + ", " + GUEST_IP), SUGGESTIONS.getFirst());
            assertEquals(429, retried.statusCode(), "偽造 " + forged + " 不該換到新的次數");
        }
    }

    @Test
    @DisplayName("反例：沒登入時送自由文字回 401、不算次數；助理的其他端點和 /api/calendar/* 也回 401")
    void guestCannotTalkFreely() throws Exception {
        BrowserSession guest = guestFrom(GUEST_IP);

        HttpResponse<String> freeText = ask(guest, "幫我把明天的行程全部刪掉");
        assertEquals(401, freeText.statusCode(), freeText.body());
        assertEquals("SIGN_IN_REQUIRED", JSON.readTree(freeText.body()).at("/code").asString());

        assertEquals(401, guest.get(URL + "/3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10").statusCode());
        assertEquals(401, guest.postJson(URL + "/dismissals", """
                {"proposalIds": []}""").statusCode());
        assertEquals(401, guest.get("/api/calendar/events?from=2030-01-01&to=2030-02-01").statusCode());
        assertEquals(401, guest.postJson("/api/calendar/events", """
                {"events": [{"title": "和 Amy 開會", "start": "2030-01-15T15:00:00", "end": "2030-01-15T16:00:00"}]}""")
                .statusCode());

        // 送錯的那次不算：3 題都還看得到
        useUp(guest);
    }

    @Test
    @DisplayName("反例：登入的人不受影響，固定問題也照常交給模型（這裡模型連不上，所以是 502），不算訪客的次數")
    void signedInUsersStillReachTheModel() throws Exception {
        jdbc.sql("DELETE FROM account").update();
        BrowserSession amy = new BrowserSession(port).forwardedFor(GUEST_IP).signUpAndLogIn("guest-test", "guest-test-pass");

        assertEquals(502, ask(amy, "幫我把明天的行程全部刪掉").statusCode());
        assertEquals(502, ask(amy, SUGGESTIONS.getFirst()).statusCode());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM guest_demo_usage").query(Integer.class).single());
    }

    @Test
    @DisplayName("http.server.requests 帶 guest 標籤：訪客的助理請求是 true；反例：登入者的助理請求、訪客打其他 API 都是 false，沒有缺標籤的")
    void guestRequestsAreTagged() throws Exception {
        assertEquals(200, ask(guestFrom(GUEST_IP), SUGGESTIONS.getFirst()).statusCode());
        assertEquals(401, guestFrom(GUEST_IP).get("/api/calendar/events?from=2030-01-01&to=2030-02-01").statusCode());
        jdbc.sql("DELETE FROM account").update();
        BrowserSession amy = new BrowserSession(port).signUpAndLogIn("tag-test", "tag-test-pass");
        assertEquals(502, ask(amy, "幫我把明天的行程全部刪掉").statusCode());

        // 觀測在回應送出之後才結束，指標可能比回應晚一點記上
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline && requests(URL, "false") == 0) {
            Thread.sleep(50);
        }
        assertTrue(requests(URL, "true") >= 1, "訪客的助理請求要帶 guest=true");
        assertTrue(requests(URL, "false") >= 1, "登入者的助理請求要帶 guest=false");
        assertTrue(meters.find("http.server.requests").tag("status", "401").tag("guest", "false").timer() != null,
                "訪客打其他 API（被擋下的 401）也是 guest=false");
        assertTrue(meters.find("http.server.requests").timers().stream().allMatch(t -> t.getId().getTag("guest") != null),
                "每個 http.server.requests 都要帶 guest 標籤，Prometheus 才收得下");
    }

    private long requests(String uri, String guest) {
        return meters.find("http.server.requests").tag("uri", uri).tag("guest", guest).timers().stream()
                .mapToLong(Timer::count).sum();
    }

    @Test
    @DisplayName("反例：沒帶 CSRF token 回 403，跟其他會改東西的請求一樣")
    void needsCsrfToken() throws Exception {
        HttpResponse<String> forged = ask(guestFrom(GUEST_IP).withoutCsrfHeader(), SUGGESTIONS.getFirst());
        assertEquals(403, forged.statusCode(), forged.body());
    }
}
