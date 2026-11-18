package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

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
 * 訪客的示範回答要整個 app 組在一起才測得出來的地方：SecurityConfiguration 開放了它、其他 API 沒有跟著開放、
 * Tomcat 照 server.forward-headers-strategy 還原的 IP、V15 的計數。
 *
 * 模型指到連不上的位址：示範回答只要呼叫了模型就會 502，回得出 200 就是沒有呼叫。
 * 問題的文字照 web/src/calendar/useAssistant.ts 的 SUGGESTIONS 寫，不引用 GuestDemo 的常數：前端送的就是這幾個字。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.openai.chat.base-url=http://127.0.0.1:1/v1")
@DisplayName("系統測試：訪客的示範回答")
class GuestDemoSystemTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestPostgres.newDatabase().register(registry);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SUGGESTIONS = List.of("明天下午3點和 Amy 開會", "明天有什麼行程？", "這週哪天下午有空？");
    private static final String URL = "/api/demo/assistant";
    private static final String GUEST_IP = "203.0.113.7";

    @LocalServerPort
    int port;

    @Value("${calendar.zone}")
    ZoneId zone;

    @Autowired
    JdbcClient jdbc;

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
            assertEquals(2 - i, body.at("/remaining").asInt());
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
    @DisplayName("反例：沒登入時送自由文字給示範回 400、不算次數；呼叫 /api/calendar/* 回 401")
    void guestCannotTalkFreely() throws Exception {
        BrowserSession guest = guestFrom(GUEST_IP);

        HttpResponse<String> freeText = ask(guest, "幫我把明天的行程全部刪掉");
        assertEquals(400, freeText.statusCode(), freeText.body());
        assertEquals("INVALID_REQUEST", JSON.readTree(freeText.body()).at("/code").asString());

        HttpResponse<String> assistant = guest.postJson("/api/calendar/assistant", """
                {"message": "明天下午3點和 Amy 開會"}""");
        assertEquals(401, assistant.statusCode(), assistant.body());
        assertEquals(401, guest.get("/api/calendar/events?from=2030-01-01&to=2030-02-01").statusCode());
        assertEquals(401, guest.postJson("/api/calendar/events", """
                {"events": [{"title": "和 Amy 開會", "start": "2030-01-15T15:00:00", "end": "2030-01-15T16:00:00"}]}""")
                .statusCode());

        // 送錯的那次不算：3 題都還看得到
        useUp(guest);
    }

    @Test
    @DisplayName("反例：沒帶 CSRF token 回 403，跟其他會改東西的請求一樣")
    void needsCsrfToken() throws Exception {
        HttpResponse<String> forged = ask(guestFrom(GUEST_IP).withoutCsrfHeader(), SUGGESTIONS.getFirst());
        assertEquals(403, forged.statusCode(), forged.body());
    }
}
