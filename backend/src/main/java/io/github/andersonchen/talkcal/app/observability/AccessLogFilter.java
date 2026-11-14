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
 * 每個 HTTP 請求記一行：方法、路徑、請求和回應的 body、狀態碼、花多久。body 跟狀態碼在同一行，Loki 查一筆就看得完整。
 *
 * 不用 Tomcat 內建的 access log（server.tomcat.accesslog.*）：Tomcat 的 access log 不走 SLF4J，寫成檔案、Apache 格式的一行文字。
 * 容器的 log 寫 stdout 給 Alloy 收，走 SLF4J 才會在 docker profile 下跟其他 log 一樣印成 ECS JSON。
 *
 * 放在 app.observability：這裡記的是整個應用的每個請求，不屬於任何一個業務模組。
 */
@Component
// 緊貼在 Spring Security（DEFAULT_FILTER_ORDER = -100）外面：
// - 在 Spring Security 外面：被 Spring Security 擋下的請求（沒登入 401、沒帶 CSRF token 403）也要留紀錄，
//   暴力試密碼、拿別人的 cookie 亂送請求的最需要被看到。排在裡面的話，這些請求在 log 裡完全隱形
// - 在 ServerHttpObservationFilter（HIGHEST_PRECEDENCE + 1）裡面：trace id 只在這個 filter 的範圍內放進 MDC，
//   排在外面的話這行 log 沒有 traceId，Loki 上「到 Tempo 看這個請求」的按鈕就不會出現（AccessLogTraceIdTest 會檢查）
// - 在 characterEncodingFilter（HIGHEST_PRECEDENCE）裡面：官方文件說會讀 body 的 filter 不要放在 HIGHEST_PRECEDENCE，會跟編碼設定衝突
// - 會包裝 request 的 filter，官方規定 order 不可大於 REQUEST_WRAPPER_FILTER_MAX_ORDER（0）
// 用常數算：Spring Security 的位置改了，這裡跟著動
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
final class AccessLogFilter extends OncePerRequestFilter {

    // 不用類別全名：Loki 裡用 log_logger="access" 篩，要單獨調等級也只要 logging.level.access=WARN
    private static final Logger log = LoggerFactory.getLogger("access");

    // 使用者的話是一兩句、助理的回覆是幾段文字加幾張卡片，8 KB 綽綽有餘；上限是怕大量內容進出時，log 一行太長。
    // 超過的部分照樣傳遞，只是不記
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private static final String CREDENTIAL_PATHS = "/api/auth/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // request 的 body 是串流，controller 的 @RequestBody 讀走之後就讀不到了。
        // ContentCachingRequestWrapper 在 controller 讀的時候順手存一份；讀了才存，所以只能在 chain 走完之後拿
        ContentCachingRequestWrapper cachingRequest = new ContentCachingRequestWrapper(request, MAX_BODY_BYTES);
        // ContentCachingResponseWrapper 把 controller 寫的 byte 全部攔在記憶體裡、不送出，
        // 最後一定要 copyBodyToResponse()，漏了的話客戶端收到空的回應
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        long start = System.nanoTime();
        try {
            chain.doFilter(cachingRequest, cachingResponse);
        } finally {
            // finally：後面丟出例外的請求也要留紀錄，出事的最需要被看到
            long elapsedNanos = System.nanoTime() - start;
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            // 註冊、登入的請求裡有密碼：log 會進 Loki，記了等於密碼明碼存在另一個地方。回應裡沒有密碼，照記
            String requestBody = request.getRequestURI().startsWith(CREDENTIAL_PATHS)
                    ? "（不記：可能有密碼）"
                    : bodyOf(cachingRequest.getContentType(), cachingRequest.getContentAsByteArray());
            // copyBodyToResponse() 送出之後會清掉攔下的 buffer，所以先取內容
            String responseBody = bodyOf(cachingResponse.getContentType(), cachingResponse.getContentAsByteArray());
            int status = cachingResponse.getStatus();
            cachingResponse.copyBodyToResponse();
            // 用 key-value：ECS JSON 會把這些 key 變成獨立欄位，Loki 才能寫 `| json | http_response_status_code >= 500`，
            // 不必用 regex 從句子裡挖。欄位名照 ECS，event.duration 在 ECS 規定是奈秒
            log.atInfo()
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", request.getRequestURI())
                    .addKeyValue("http.request.body.content", requestBody)
                    .addKeyValue("http.response.status_code", status)
                    .addKeyValue("http.response.body.content", responseBody)
                    .addKeyValue("event.duration", elapsedNanos)
                    // 本機開發不印 JSON，終端機上看的是這句
                    .log("{} {} {} -> {} {} ({} ms)",
                            request.getMethod(), request.getRequestURI(), requestBody, status, responseBody, elapsedMillis);
        }
    }

    /**
     * 只記 JSON：Swagger UI 會載入 JS、CSS，記下來只是雜訊，API 進出都是 JSON。
     * JSON 規定是 UTF-8，不必看宣告的編碼。
     */
    private static String bodyOf(String contentType, byte[] content) {
        if (contentType == null || !contentType.contains("json")) {
            return "";
        }
        int length = Math.min(content.length, MAX_BODY_BYTES);
        return new String(content, 0, length, StandardCharsets.UTF_8);
    }
}
