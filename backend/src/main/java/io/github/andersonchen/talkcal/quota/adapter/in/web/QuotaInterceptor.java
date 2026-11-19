package io.github.andersonchen.talkcal.quota.adapter.in.web;

import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase.Consumed;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase.QuotaRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.security.Principal;
import java.util.Objects;
import java.util.function.Function;

/**
 * 每個 API 請求進 controller 之前先算一次每日次數，用完就丟 QuotaExceededException，不進 controller
 * （轉成 429 的是 QuotaExceededAdvice）。
 *
 * 放在 Spring MVC 的 interceptor，不放 servlet filter：這時已經過了 Spring Security，請求的帳號和等級都已經確定，
 * 被 Spring Security 擋掉的請求（401、CSRF 的 403）也不會被算一次；Spring 也已經選好 controller 的方法，
 * 規則比對的路徑樣板見 QuotaRule。
 *
 * 回應是 4xx、5xx 時退回這一次：模型出錯、資料庫出錯不是使用者的問題；送錯的請求（400、409）什麼都沒做成，
 * 也不該用掉次數。被每日次數擋下的那次在 ConsumeQuotaUseCase 裡就退回了。
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
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern)) {
            return true;
        }
        Principal principal = request.getUserPrincipal();
        QuotaRequest asked = new QuotaRequest(request.getMethod(), pattern, tierOf.apply(request),
                ClientAddress.key(request.getRemoteAddr()), principal == null ? null : principal.getName());
        quota.consume(asked).ifPresent(consumed -> request.setAttribute(CONSUMED, consumed));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(CONSUMED) instanceof Consumed consumed
                && (ex != null || response.getStatus() >= HttpStatus.BAD_REQUEST.value())) {
            quota.refund(consumed);
        }
    }
}
