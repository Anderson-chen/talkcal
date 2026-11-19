package io.github.andersonchen.talkcal.quota.adapter.in.web;

import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase.Consumed;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase.QuotaRequest;
import io.github.andersonchen.talkcal.quota.application.port.in.QuotaExceededException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.security.Principal;
import java.util.Objects;
import java.util.function.Function;

/**
 * 每個 API 請求進 controller 之前先算一次每日次數，用完就直接回 429，不進 controller。
 *
 * 放在 Spring MVC 的 interceptor，不放 servlet filter：這時已經過了 Spring Security，請求的帳號和等級都已經確定，
 * 被 Spring Security 擋掉的請求（401、CSRF 的 403）也不會被算一次。
 *
 * 回應是 5xx 時退回這一次：模型出錯、資料庫出錯不是使用者的問題，不該用掉他的次數。
 */
public final class QuotaInterceptor implements HandlerInterceptor {

    private static final String CONSUMED = QuotaInterceptor.class.getName() + ".consumed";

    private final ConsumeQuotaUseCase quota;
    private final Function<HttpServletRequest, String> tierOf;

    /** tierOf：請求者的等級（guest、free、paid，沒登入是 none），由組裝的地方決定從哪裡取 */
    public QuotaInterceptor(ConsumeQuotaUseCase quota, Function<HttpServletRequest, String> tierOf) {
        this.quota = Objects.requireNonNull(quota, "quota 不可為 null");
        this.tierOf = Objects.requireNonNull(tierOf, "tierOf 不可為 null");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Principal principal = request.getUserPrincipal();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        QuotaRequest asked = new QuotaRequest(request.getMethod(), path, tierOf.apply(request),
                ClientAddress.key(request.getRemoteAddr()), principal == null ? null : principal.getName());
        try {
            quota.consume(asked).ifPresent(consumed -> request.setAttribute(CONSUMED, consumed));
            return true;
        } catch (QuotaExceededException e) {
            long seconds = Math.max(1, e.retryAfter().toSeconds());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            response.setContentType("application/json;charset=UTF-8");
            // 格式跟各 controller 的錯誤一樣（{"error", "code", "retryAfterSeconds"}），前端照 code 判斷要不要提示註冊
            response.getWriter().write("{\"error\":\"" + escape(e.getMessage()) + "\",\"code\":\"QUOTA_EXCEEDED\","
                    + "\"retryAfterSeconds\":" + seconds + "}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(CONSUMED) instanceof Consumed consumed
                && (ex != null || response.getStatus() >= HttpStatus.INTERNAL_SERVER_ERROR.value())) {
            quota.refund(consumed);
        }
    }

    // 訊息來自規則表，是維護者寫的，但還是跳脫引號和反斜線，免得寫出壞掉的 JSON
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
