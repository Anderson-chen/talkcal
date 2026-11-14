package io.github.andersonchen.talkcal.app.observability;

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
 * Loki 上「到 Tempo 看這個請求」的按鈕，靠 access log 帶著 traceId。AccessLogFilter 要排在 Spring 開 trace 的 filter 裡面才有，
 * 那個 filter 排在哪由 Spring 決定。排錯了不會報錯，log 只會少一個欄位，所以測最終結果，不只相信 @Order 的常數。
 *
 * 啟動真的 server：MockMvc 自己組 filter 鏈，驗到的不一定是 Servlet 容器裡那一條。
 * 請求沒登入也沒帶 CSRF token，在 Spring Security 就被擋下，用不到模型和資料庫。
 * 資料庫指到沒人在聽的埠、Flyway 關掉（啟動時只有 Flyway 會連資料庫）；properties 要跟 ActuatorPortTest 一字不差，兩個測試才共用同一個 context。
 *
 * 看 log 事件的 MDC，不把輸出改成 ECS JSON 再找 traceId：ECS 的 traceId 就是從 MDC 抄的。
 * 而且改了 log 格式會留在整個測試 JVM，之後執行的 AccessLogFilterTest 會看到 JSON 而失敗。
 *
 * spring-boot-micrometer-tracing-test 在測試裡關掉匯出，trace id 照樣產生。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.flyway.enabled=false", "spring.datasource.url=jdbc:postgresql://localhost:1/talkcal"})
@DisplayName("access log 帶著 traceId")
class AccessLogTraceIdTest {

    // OpenTelemetry 的 trace id 是 16 byte，印成 32 個十六進位字元
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    // AccessLogFilter 送出回應之後才記 log，客戶端收到回應時那筆可能還沒到
    private static final Duration WAIT_FOR_LOG = Duration.ofSeconds(5);

    @LocalServerPort
    int port;

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
            // Tomcat 的執行緒會同時往 list 寫，複製一份再讀
            List<ILoggingEvent> events = List.copyOf(captured.list);
            if (!events.isEmpty()) {
                return events.getLast();
            }
            Thread.sleep(50);
        }
        return fail("等了 " + WAIT_FOR_LOG.toSeconds() + " 秒都沒看到 access log");
    }
}
