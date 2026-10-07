package eat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * AccessLogFilter 的測試，直接餵 Mock 的 request/response，不起 Spring。
 *
 * 跟 CalendarControllerTest 這類 web 切片不同：這個 filter 只用到 Servlet API，路由、綁定都跟它無關，
 * 起 MockMvc 驗不到更多東西。要驗的只有「有沒有寫出那一行、該跳過的有沒有跳過」，
 * 所以用 OutputCaptureExtension 抓 stdout 來看。
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AccessLogFilter")
class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();

    @Test
    @DisplayName("記下方法、路徑與狀態碼")
    void logsMethodPathAndStatus(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/calendar/assistant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(502);

        filter.doFilter(request, response, new MockFilterChain());

        assertTrue(output.getOut().contains("POST /api/calendar/assistant"), output.getOut());
        assertTrue(output.getOut().contains("-> 502"), output.getOut());
    }

    @Test
    @DisplayName("記下請求的 body——controller 讀走之後也拿得到")
    void logsRequestBody(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/calendar/assistant");
        request.setContentType("application/json");
        request.setContent("{\"message\":\"明天七點和 Amy 見面\"}".getBytes(StandardCharsets.UTF_8));
        // 扮演 controller：把 body 整條讀完。ContentCachingRequestWrapper 是「讀了才存」，
        // 用不讀 body 的 MockFilterChain 會測不出東西
        FilterChain readsBody = (req, res) -> req.getInputStream().readAllBytes();

        filter.doFilter(request, new MockHttpServletResponse(), readsBody);

        assertTrue(output.getOut().contains("{\"message\":\"明天七點和 Amy 見面\"}"), output.getOut());
    }

    @Test
    @DisplayName("記下回應的 body，而且照樣送到客戶端")
    void logsResponseBodyAndStillSendsIt(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/calendar/assistant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // 扮演 controller：寫出一段 JSON 回覆
        FilterChain writesReply = (req, res) -> {
            res.setContentType("application/json");
            res.getOutputStream().write("{\"reply\":\"幫你排好了\"}".getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(request, response, writesReply);

        assertTrue(output.getOut().contains("{\"reply\":\"幫你排好了\"}"), output.getOut());
        // 被攔下的內容一定要還給客戶端，不然 log 有了、回應卻是空的
        assertEquals("{\"reply\":\"幫你排好了\"}", response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("actuator 的請求不記，免得被 Alloy 的指標抓取淹掉")
    void skipsActuator(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertFalse(output.getOut().contains("/actuator/prometheus"), output.getOut());
    }
}
