package io.github.andersonchen.talkcal.app.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 每個回應都帶著這個請求的 trace id（X-Trace-Id header）：使用者說「剛剛那次怪怪的」時，
 * 前端把它顯示出來、讓使用者複製給我們，拿去 Grafana 的 Tempo 一貼就是那一條 trace（#42）。
 *
 * 為什麼放 header、不放錯誤回應的 body：
 * - 每一種回應都有，不必一個一個補：成功的、controller 回的 400/404、Spring Security 自己擋下的 401/403、
 *   沒有 body 的 204 刪除，以及沒走到 controller 就炸掉的 500。錯誤的 body 是好幾個地方各自組的，放 body 就得每處都記得加
 * - 不動 API 的契約：前端只在 http.ts 的 send() 一個地方讀，各 API 的回應型別不必多一個欄位
 * 同源（nginx 一個入口，見 web/nginx.conf），所以 JavaScript 讀得到這個自訂 header，不必開 Access-Control-Expose-Headers。
 *
 * 為什麼只給 trace id、不回整串 W3C traceparent（00-traceid-spanid-flags）：
 * 要給人複製、在 Tempo 搜的只有 trace id；span id 是 app 這一段的，使用者用不到，多印只是讓那串字更長更難念。
 * W3C 有一個專門回給客戶端的 traceresponse header，但它還在草案階段，瀏覽器和工具都不會讀它，
 * 照它的格式也只是把同一個 trace id 包得更長。所以用最直白的名字。
 *
 * trace id 不是祕密：它是隨機產生的，拿到它也看不到任何東西（Tempo 不對外開放），所以可以放心給使用者看。
 */
@Component
// 排序：在 AccessLogFilter（Spring Security - 1）外面一層，理由跟它一樣 ——
// - 在 Spring Security 外面：被它擋下的 401/403 也帶 trace id，「怎麼一直叫我重新登入」也查得到
// - 在 Spring 開 trace 的 ServerHttpObservationFilter（HIGHEST_PRECEDENCE + 1）裡面：這時 trace 已經開好，拿得到 id。
//   排在它外面的話 currentSpan() 是 null，header 就悄悄不見了。TraceIdResponseFilterTest 守著這件事
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 2)
final class TraceIdResponseFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Trace-Id";

    private final Tracer tracer;

    TraceIdResponseFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 在 chain 之前就放：header 只能在回應送出（commit）前加，等 controller 寫完 body 再加就來不及了。
        // trace 在請求進來時就開好了，id 從頭到尾不變，先放不會放錯
        Span span = tracer.currentSpan();
        // 沒有 trace 就不放（例如 /actuator 被 ObservationConfiguration 擋掉不開 trace）：
        // 寧可沒有，也不要給一個在 Tempo 裡查不到的 id
        if (span != null) {
            response.setHeader(HEADER, span.context().traceId());
        }
        chain.doFilter(request, response);
    }
}
