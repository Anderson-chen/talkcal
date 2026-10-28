package io.github.andersonchen.talkcal.app.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
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
 * 為什麼放在 app.observability、不放在某個模組的 adapter.in.web：它記的是整個應用的每一個請求，
 * 不屬於任何一門業務，是全站的觀測設定。core 照樣不知道有 HTTP 這回事。
 *
 * 請求內容（body）也一起記在同一行：一次請求的「送了什麼」和「回幾號、花多久」放在一起，
 * 在 Loki 查一筆就看得完整，不必再拿時間去對另一行 log。
 */
@Component
// 排序：緊貼在 Spring Security 外面（它是 SecurityFilterProperties.DEFAULT_FILTER_ORDER = -100，這裡是 -101）。
// - 在 Spring Security 外面：被它擋下的請求（沒登入 401、沒帶 CSRF token 403）也要留下紀錄 ——
//   有人在暴力試密碼、或拿別人的 cookie 亂打，最需要被看到。排在它裡面的話，這些請求在 log 裡完全隱形
//   （#103 加上 Spring Security 時 AccessLogTraceIdTest 就是這樣失敗的：沒帶 CSRF 的 POST 被擋下，一行 log 都沒有）
// - 在 Spring 開 trace 的 ServerHttpObservationFilter 裡面：它排在最外層附近（HIGHEST_PRECEDENCE + 1），
//   trace id 只在它的範圍內放進 MDC。排在它外面的話，這行 access log 印出來時 trace 已經結束，ECS JSON 裡就沒有 traceId，
//   Loki 上那顆「到 Tempo 看這個請求」的按鈕也就不會出現。AccessLogTraceIdTest 守著這件事。
// - 在 characterEncodingFilter（HIGHEST_PRECEDENCE）裡面：官方文件說會讀 body 的 filter 別放在 HIGHEST_PRECEDENCE，會跟編碼設定打架。
// - 會包裝 request 的 filter，官方規定 order 不可大於 REQUEST_WRAPPER_FILTER_MAX_ORDER（0）：-101 在這條線以內。
// 用常數算、不寫死 -101：Spring Security 的位置改了，這裡跟著動。
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
final class AccessLogFilter extends OncePerRequestFilter {

    // logger 名字固定成 "access"，不用類別全名：查詢時好篩（Loki 裡 log_logger="access"），
    // 之後想單獨調它的等級或關掉也只要一行 logging.level.access=WARN。
    private static final Logger log = LoggerFactory.getLogger("access");

    // body 最多記這麼多 byte。使用者的話是一兩句、助理的回覆是幾段文字加幾張提議卡片，8 KB 綽綽有餘；
    // 設上限是怕一大包進出時，log 一行就爆掉。超過的部分照樣傳遞，只是不記。
    private static final int MAX_BODY_BYTES = 8 * 1024;

    // 這個路徑底下的請求內容不記：註冊、登入會帶著密碼
    private static final String CREDENTIAL_PATHS = "/api/auth/";

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
            // 註冊、登入的請求裡有密碼（JSON 或表單），一律不記：log 會被 Alloy 收進 Loki，
            // 寫進去就等於明碼存在另一個地方、看 log 的人都看得到。回應照記（裡面沒有密碼）
            String requestBody = request.getRequestURI().startsWith(CREDENTIAL_PATHS)
                    ? "（不記：可能有密碼）"
                    : bodyOf(cachingRequest.getContentType(), cachingRequest.getContentAsByteArray());
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
}
