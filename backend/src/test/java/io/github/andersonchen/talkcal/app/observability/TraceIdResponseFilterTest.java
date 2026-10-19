package io.github.andersonchen.talkcal.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 回應帶著 X-Trace-Id，而且就是這個請求的那一條 trace —— 使用者複製給我們的 id，在 Tempo 要查得到同一個請求。
 *
 * 跟 AccessLogTraceIdTest 同一個道理起真的 server：filter 在 Servlet 容器裡排在哪，決定拿不拿得到 trace，
 * 排錯了不會報錯、header 只是悄悄不見。不需要模型也不需要資料庫（見 AccessLogTraceIdTest 的說明）。
 *
 * 「就是這一條」怎麼驗：同一個請求的 access log 的 MDC 裡也有 traceId（Loki 那筆 log 就是靠它跳到 Tempo），
 * 兩個相等，表示 header 給的跟送進 Tempo 的是同一條。只驗「長得像 trace id」的話，給錯一條也會過。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=false")
@DisplayName("回應帶著這個請求的 trace id")
class TraceIdResponseFilterTest {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    private static final Duration WAIT_FOR_LOG = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("access");
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void startCapturing() {
        captured.start();
        accessLogger.addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        accessLogger.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("出錯的回應帶著 trace id，跟這個請求的 access log 是同一條")
    void errorResponseCarriesItsOwnTraceId() throws Exception {
        // 空白訊息：助理在叫模型之前就回錯（沒登入、沒帶 CSRF 也一樣是錯誤回應），不需要模型
        HttpResponse<Void> response = send(HttpRequest.newBuilder(uri("/api/calendar/assistant"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"   \"}")));

        String traceId = traceIdOf(response);
        assertTrue(response.statusCode() >= 400, "這個請求應該是錯誤回應，實際是 " + response.statusCode());
        assertEquals(awaitAccessLog().getMDCPropertyMap().get("traceId"), traceId,
                "header 的 trace id 跟 access log 的不是同一條");
    }

    @Test
    @DisplayName("被 Spring Security 擋下的 401 也帶著 trace id")
    void rejectedBySecurityStillCarriesTraceId() throws Exception {
        HttpResponse<Void> response = send(HttpRequest.newBuilder(uri("/api/calendar/events?from=2026-10-01&to=2026-11-01")).GET());

        assertEquals(401, response.statusCode());
        traceIdOf(response);
    }

    @Test
    @DisplayName("每個請求各自一個 trace id，不會共用")
    void eachRequestHasItsOwnTraceId() throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/auth/me")).GET();

        assertNotEquals(traceIdOf(send(request)), traceIdOf(send(request)));
    }

    @Test
    @DisplayName("反例：沒有開 trace 的請求（/actuator）不給 trace id —— 不給一個在 Tempo 查不到的 id")
    void noTraceNoHeader() throws Exception {
        // 回幾號不重要（測試裡沒有資料庫，health 是 503），重要的是有沒有 header
        HttpResponse<Void> response = send(HttpRequest.newBuilder(uri("/actuator/health")).GET());

        assertEquals(Optional.empty(), response.headers().firstValue(TraceIdResponseFilter.HEADER));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<Void> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String traceIdOf(HttpResponse<?> response) {
        String traceId = response.headers().firstValue(TraceIdResponseFilter.HEADER)
                .orElseGet(() -> fail("回應沒有 " + TraceIdResponseFilter.HEADER + "，header 是：" + response.headers().map()));
        assertTrue(TRACE_ID.matcher(traceId).matches(), "不像 trace id：" + traceId);
        return traceId;
    }

    private ILoggingEvent awaitAccessLog() throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT_FOR_LOG);
        while (Instant.now().isBefore(deadline)) {
            List<ILoggingEvent> events = List.copyOf(captured.list);
            if (!events.isEmpty()) {
                return events.getLast();
            }
            Thread.sleep(50);
        }
        return fail("等了 " + WAIT_FOR_LOG.toSeconds() + " 秒都沒看到 access log");
    }
}
