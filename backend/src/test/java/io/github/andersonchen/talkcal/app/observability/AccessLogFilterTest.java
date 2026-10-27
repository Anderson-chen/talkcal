package io.github.andersonchen.talkcal.app.observability;

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
    @DisplayName("請求、回應的內容都不記（#52）：使用者的話、助理的回覆、行程標題會跟著 log 進 Loki；反例：大小、狀態碼照記，回應照樣送到客戶端")
    void doesNotLogBodies(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/calendar/assistant");
        request.setContentType("application/json");
        byte[] said = "{\"message\":\"明天陪王小明看身心科 0912-345-678\"}".getBytes(StandardCharsets.UTF_8);
        request.setContent(said);
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] reply = "{\"reply\":\"已提醒你打給王小明\"}".getBytes(StandardCharsets.UTF_8);
        // 扮演 controller：讀完請求、寫出回覆
        FilterChain controller = (req, res) -> {
            req.getInputStream().readAllBytes();
            res.setContentType("application/json");
            res.getOutputStream().write(reply);
        };

        filter.doFilter(request, response, controller);

        assertFalse(output.getOut().contains("王小明"), output.getOut());
        assertFalse(output.getOut().contains("0912"), output.getOut());
        assertTrue(output.getOut().contains("POST /api/calendar/assistant -> 200"), output.getOut());
        assertTrue(output.getOut().contains(said.length + " B in, " + reply.length + " B out"), output.getOut());
        // 只數、不攔：內容要原封不動送到客戶端
        assertEquals(new String(reply, StandardCharsets.UTF_8), response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("註冊、登入的密碼也不會出現（JSON 和表單兩種都試）；反例：請求本身照樣有紀錄，誰在試登入要看得到")
    void doesNotLogCredentials(CapturedOutput output) throws Exception {
        FilterChain readsBody = (req, res) -> req.getInputStream().readAllBytes();

        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/api/auth/register");
        register.setContentType("application/json");
        register.setContent("{\"username\":\"amy\",\"password\":\"secret-pass-1\"}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(register, new MockHttpServletResponse(), readsBody);

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/auth/login");
        login.setContentType("application/x-www-form-urlencoded");
        login.setContent("username=amy&password=secret-pass-2".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(login, new MockHttpServletResponse(), readsBody);

        assertFalse(output.getOut().contains("secret-pass"), output.getOut());
        assertTrue(output.getOut().contains("POST /api/auth/login -> 200"), output.getOut());
    }

    @Test
    @DisplayName("query string 不記：之後加了搜尋，查的字會在網址上；反例：路徑照記")
    void doesNotLogQueryString(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/calendar/events");
        request.setQueryString("q=%E7%8E%8B%E5%B0%8F%E6%98%8E");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertFalse(output.getOut().contains("q="), output.getOut());
        assertTrue(output.getOut().contains("GET /api/calendar/events -> 200"), output.getOut());
    }

    @Test
    @DisplayName("用 getWriter() 寫的回應（Spring Security 擋下時的錯誤 JSON）也數得到大小，而且整段送到客戶端")
    void countsWriterOutput(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain rejects = (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401);
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("{\"error\":\"請先登入\"}");
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"), response, rejects);

        int bytes = "{\"error\":\"請先登入\"}".getBytes(StandardCharsets.UTF_8).length;
        assertTrue(output.getOut().contains("-> 401") && output.getOut().contains(bytes + " B out"), output.getOut());
        assertEquals("{\"error\":\"請先登入\"}", response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("actuator 的請求不記，免得被 Alloy 的指標抓取淹掉")
    void skipsActuator(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertFalse(output.getOut().contains("/actuator/prometheus"), output.getOut());
    }
}
