package io.github.andersonchen.talkcal.quota.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 退不退這一次只看回應：ConsumeQuotaUseCase 換成假的，每次都算在同一份計數上，記下退了幾次。
 */
@DisplayName("QuotaInterceptor")
class QuotaInterceptorTest {

    /** 照扣的失敗；其他例外照常退 */
    private static final class SpentAnyway extends IllegalStateException {
    }

    private final List<ConsumeQuotaUseCase.QuotaRequest> asked = new ArrayList<>();
    private final List<ConsumeQuotaUseCase.Consumed> refunded = new ArrayList<>();

    private final ConsumeQuotaUseCase quota = new ConsumeQuotaUseCase() {
        @Override
        public Optional<Consumed> consume(QuotaRequest request) {
            asked.add(request);
            return Optional.of(new Consumed(List.of("quota:test")));
        }

        @Override
        public void refund(Consumed consumed) {
            refunded.add(consumed);
        }
    };

    private final QuotaInterceptor interceptor = new QuotaInterceptor(quota, request -> "guest", SpentAnyway.class::isInstance);

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/calendar/assistant");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/calendar/assistant");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    private void handle(MockHttpServletRequest request, int status, Exception ex) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        response.setStatus(status);
        interceptor.afterCompletion(request, response, new Object(), ex);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {200, 201, 204})
    @DisplayName("成功的回應照扣，不退")
    void successIsKept(int status) {
        handle(request(), status, null);

        assertEquals(1, asked.size());
        assertEquals(List.of(), refunded);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {400, 404, 409, 502, 503})
    @DisplayName("反例：4xx、5xx 退回這一次（送錯的請求、模型或資料庫出錯、斷路器打開、滿載）")
    void failureIsRefunded(int status) {
        handle(request(), status, null);

        assertEquals(1, refunded.size());
    }

    @Test
    @DisplayName("反例：controller 丟出沒人處理的例外（回應還沒設狀態碼）也退")
    void unhandledExceptionIsRefunded() {
        handle(request(), 200, new IllegalStateException("沒人處理"));

        assertEquals(1, refunded.size());
    }

    @Test
    @DisplayName("@ExceptionHandler 處理掉、keptOnFailure 判斷成照扣的例外：502 也不退；反例：其他例外處理掉的 502 照退")
    void keptOnFailureIsNotRefunded() {
        // DispatcherServlet 用 @ExceptionHandler 回應之後，例外放在這個屬性，afterCompletion 拿到的 ex 是 null
        MockHttpServletRequest kept = request();
        kept.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, new SpentAnyway());
        handle(kept, 502, null);
        assertEquals(List.of(), refunded);

        MockHttpServletRequest other = request();
        other.setAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE, new IllegalStateException("模型出錯"));
        handle(other, 502, null);
        assertEquals(1, refunded.size());
    }

    @Test
    @DisplayName("沒人處理、keptOnFailure 判斷成照扣的例外：也不退")
    void unhandledKeptFailureIsNotRefunded() {
        handle(request(), 200, new SpentAnyway());

        assertEquals(List.of(), refunded);
    }

    @Test
    @DisplayName("Spring 沒有選到 controller 的方法（沒有路徑樣板）：不算、也不退")
    void noHandlerIsNotCounted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/nothing");

        handle(request, 404, null);

        assertEquals(List.of(), asked);
        assertEquals(List.of(), refunded);
    }
}
