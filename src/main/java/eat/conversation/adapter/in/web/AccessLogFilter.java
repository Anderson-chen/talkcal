package eat.conversation.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Access log：每個 HTTP 請求進來、出去時記一行「誰打了什麼、帶了什麼內容、回幾號、花多久」。
 *
 * 為什麼是 Filter 而不是開 Tomcat 內建的 access log（server.tomcat.accesslog.*）：
 * Tomcat 那份不走 SLF4J，預設寫成檔案、格式是 Apache 那種一行文字。
 * 容器裡的 log 一律寫 stdout 給 Alloy 收（見 ops/alloy/config.alloy），
 * 走 SLF4J 才會跟其他 log 一樣在 docker profile 下印成 ECS JSON，Loki 用 `| json` 就拆得開。
 *
 * 為什麼放在 adapter.in.web：「HTTP 請求」本來就只存在這一圈，core 不知道有 HTTP 這回事。
 *
 * 請求內容（body）也一起記在同一行：一次提問的「問了什麼」和「回幾號、花多久」放在一起，
 * 在 Loki 查一筆就看得完整，不必再拿時間去對另一行 log。
 */
@Component
// 排在最前面：耗時才會涵蓋後面所有 filter（含 Spring 自己量 http.server.requests 的那個），
// 跟 Prometheus 上看到的延遲是同一段，兩邊對得起來。
@Order(Ordered.HIGHEST_PRECEDENCE)
final class AccessLogFilter extends OncePerRequestFilter {

    // logger 名字固定成 "access"，不用類別全名：查詢時好篩（Loki 裡 log_logger="access"），
    // 之後想單獨調它的等級或關掉也只要一行 logging.level.access=WARN。
    private static final Logger log = LoggerFactory.getLogger("access");

    // body 最多記這麼多 byte。提問是一兩句話、回覆是幾段文字，8 KB 綽綽有餘；
    // 設上限是怕一大包進出時，log 一行就爆掉。超過的部分照樣傳遞，只是不記。
    private static final int MAX_BODY_BYTES = 8 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // request 的 body 是一條串流，只能讀一次——controller 的 @RequestBody 讀走之後，這裡就讀不到了。
        // 包一層 ContentCachingRequestWrapper：controller 照常讀，讀過的 byte 順手被存一份，事後再從這份拿出來記。
        // 注意它是「讀了才存」，所以 body 只能在 chain 走完之後拿，不能在前面拿。
        ContentCachingRequestWrapper cachingRequest = new ContentCachingRequestWrapper(request, MAX_BODY_BYTES);
        // response 那頭反過來：controller 寫出去的 byte 一送出就拿不回來了。
        // ContentCachingResponseWrapper 先把它們全部攔在記憶體裡，不送給客戶端，
        // 所以最後一定要 copyBodyToResponse() 把攔下的內容真的送出去——漏了這步，客戶端會收到空的回應。
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        try {
            chain.doFilter(cachingRequest, cachingResponse);
        } finally {
            // 寫在 finally：就算後面丟出例外，這個請求也要留下紀錄——出事的請求最需要被看到
            long elapsedNanos = System.nanoTime() - start;
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            String requestBody = bodyOf(cachingRequest.getContentType(), cachingRequest.getContentAsByteArray());
            // 先取內容再 copy：copyBodyToResponse() 送出之後會把攔下的 buffer 清掉
            String responseBody = bodyOf(cachingResponse.getContentType(), cachingResponse.getContentAsByteArray());
            int status = cachingResponse.getStatus();
            cachingResponse.copyBodyToResponse();
            // 用 key-value 而不是只把數字塞進訊息字串：ECS JSON 會把它們變成獨立欄位，
            // Loki 才能寫 `| json | http_response_status_code >= 500` 這種條件，不必用 regex 從句子裡挖。
            // 欄位名照 ECS 的慣例取，event.duration 在 ECS 規定是奈秒。
            log.atInfo()
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", request.getRequestURI())
                    .addKeyValue("http.request.body.content", requestBody)
                    .addKeyValue("http.response.status_code", status)
                    .addKeyValue("http.response.body.content", responseBody)
                    .addKeyValue("event.duration", elapsedNanos)
                    // 訊息本身仍然寫成人看得懂的一句：本機開發不開 JSON，終端機上看的就是這句
                    .log("{} {} {} -> {} {} ({} ms)",
                            request.getMethod(), request.getRequestURI(), requestBody, status, responseBody, elapsedMillis);
        }
    }

    /**
     * 只記 JSON 的 body。打開 Swagger UI 時會載入一包包 JS/CSS，那些記下來只是雜訊；
     * 我們的 API 進出都是 JSON，真正想看的內容都在這裡面。
     * JSON 規定是 UTF-8，所以不必看宣告的編碼。超過上限就截斷。
     */
    private static String bodyOf(String contentType, byte[] content) {
        if (contentType == null || !contentType.contains("json")) {
            return "";
        }
        int length = Math.min(content.length, MAX_BODY_BYTES);
        return new String(content, 0, length, StandardCharsets.UTF_8);
    }

    /**
     * actuator 不記：Prometheus 每 15 秒來抓一次 /actuator/prometheus，
     * 全記下來的話 access log 大半是它，真正的提問反而被淹掉。
     * 健康檢查、指標本身就有 Prometheus 盯著，不缺這份紀錄。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }
}
