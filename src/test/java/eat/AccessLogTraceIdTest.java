package eat;

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
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * access log 那一筆帶著 traceId —— Loki 上「到 Tempo 看這個請求」那顆按鈕就靠它。
 *
 * 這件事成不成立，取決於 AccessLogFilter 有沒有排在 Spring 開 trace 的那個 filter「裡面」，
 * 而那個 filter 排在哪是 Spring 決定的。順序錯了不會有任何錯誤訊息，log 只是悄悄少一個欄位，
 * 所以要有測試守著最終結果，而不是只相信 @Order 上的那個常數。
 *
 * 為什麼起真的 server（RANDOM_PORT）而不用 MockMvc：要驗的正是 Servlet 容器裡 filter 的真實排序，
 * MockMvc 自己組 filter 鏈，驗到的可能不是正式環境那一條。
 * 不需要模型也不需要資料庫：送空白訊息，助理在讀記憶、叫模型之前就回 400。
 * 資料庫連線池要等第一次查詢才真的連，只有 Flyway 會在啟動時連，所以把它關掉。
 *
 * 為什麼看 log 事件的 MDC，而不是把輸出開成 ECS JSON 再找 "traceId"：
 * ECS 的 traceId 欄位就是從 MDC 照抄的，看 MDC 等於看源頭。而改 log 格式會留在整個測試 JVM 裡，
 * 排在後面的 AccessLogFilterTest 會突然看到 JSON 而失敗 —— 測試之間不該互相影響。
 *
 * trace 不會真的往外送：spring-boot-micrometer-tracing-test 在測試裡把匯出關掉，但 trace id 照樣產生。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=false")
@DisplayName("access log 帶著 traceId")
class AccessLogTraceIdTest {

    // OpenTelemetry 的 trace id：16 byte，印成 32 個十六進位字元
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    // AccessLogFilter 是先把回應送出、再記 log，所以客戶端收到回應時那筆可能還沒記，要等一下
    private static final Duration WAIT_FOR_LOG = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

    // 直接掛在 "access" 這個 logger 上收事件，不碰 stdout
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
    @DisplayName("一個請求的 access log，MDC 裡帶著那個請求的 traceId")
    void accessLogCarriesTraceId() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/calendar/assistant"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"   \"}"))
                .build();
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());

        ILoggingEvent accessLog = awaitAccessLog();
        String traceId = accessLog.getMDCPropertyMap().get("traceId");
        assertTrue(traceId != null && TRACE_ID.matcher(traceId).matches(),
                "access log 沒有 traceId，MDC 是：" + accessLog.getMDCPropertyMap());
    }

    private ILoggingEvent awaitAccessLog() throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT_FOR_LOG);
        while (Instant.now().isBefore(deadline)) {
            // ListAppender 的 list 會被 Tomcat 的執行緒寫入，複製一份再讀
            List<ILoggingEvent> events = List.copyOf(captured.list);
            if (!events.isEmpty()) {
                return events.getLast();
            }
            Thread.sleep(50);
        }
        return fail("等了 " + WAIT_FOR_LOG.toSeconds() + " 秒都沒看到 access log");
    }
}
