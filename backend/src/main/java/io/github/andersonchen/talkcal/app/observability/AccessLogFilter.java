package io.github.andersonchen.talkcal.app.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * Access log：每個 HTTP 請求進來、出去時記一行「誰打了什麼、回幾號、花多久、多大」。
 *
 * 為什麼是 Filter 而不是開 Tomcat 內建的 access log（server.tomcat.accesslog.*）：
 * Tomcat 那份不走 SLF4J，預設寫成檔案、格式是 Apache 那種一行文字。
 * 容器裡的 log 一律寫 stdout 給 Alloy 收（見 ops/alloy/config.alloy），
 * 走 SLF4J 才會跟其他 log 一樣在 docker profile 下印成 ECS JSON，Loki 用 `| json` 就拆得開。
 *
 * 為什麼放在 app.observability、不放在某個模組的 adapter.in.web：它記的是整個應用的每一個請求，
 * 不屬於任何一門業務，跟 app.config 的 ObservationConfiguration 一樣是全站的觀測設定。core 照樣不知道有 HTTP 這回事。
 *
 * 請求、回應的內容（body）不記，只記大小（#52）。以前整包記下來：使用者對助理說的話、助理的回覆、
 * 行程標題和備註（名字、電話、email、看什麼病）全都跟著進了 Loki ——
 * 看 log 的人都看得到，保留期限、權限也跟資料庫不一樣，使用者要求刪除時也刪不到那裡（OWASP LLM02）。
 * 要查「那個人送了什麼」：請使用者給畫面上的追蹤碼，用 trace id 找到請求，內容在資料庫（他自己的資料）裡看。
 * 證據和查法見 docs/privacy.md。
 */
@Component
// 排序：緊貼在 Spring Security 外面（它是 SecurityFilterProperties.DEFAULT_FILTER_ORDER = -100，這裡是 -101）。
// - 在 Spring Security 外面：被它擋下的請求（沒登入 401、沒帶 CSRF token 403）也要留下紀錄 ——
//   有人在暴力試密碼、或拿別人的 cookie 亂打，最需要被看到。排在它裡面的話，這些請求在 log 裡完全隱形
//   （#103 加上 Spring Security 時 AccessLogTraceIdTest 就是這樣失敗的：沒帶 CSRF 的 POST 被擋下，一行 log 都沒有）
// - 在 Spring 開 trace 的 ServerHttpObservationFilter 裡面：它排在最外層附近（HIGHEST_PRECEDENCE + 1），
//   trace id 只在它的範圍內放進 MDC。排在它外面的話，這行 access log 印出來時 trace 已經結束，ECS JSON 裡就沒有 traceId，
//   Loki 上那顆「到 Tempo 看這個請求」的按鈕也就不會出現。AccessLogTraceIdTest 守著這件事。
// - 會包裝 request、response 的 filter（這裡包 response 來數大小），官方規定 order 不可大於 REQUEST_WRAPPER_FILTER_MAX_ORDER（0）：-101 在這條線以內。
// 用常數算、不寫死 -101：Spring Security 的位置改了，這裡跟著動。
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1)
final class AccessLogFilter extends OncePerRequestFilter {

    // logger 名字固定成 "access"，不用類別全名：查詢時好篩（Loki 裡 log_logger="access"），
    // 之後想單獨調它的等級或關掉也只要一行 logging.level.access=WARN。
    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 回應的大小要等寫完才知道，又不想為了一個數字把整包攔在記憶體裡（以前的 ContentCachingResponseWrapper）：
        // 包一層只數 byte、照常往外送的 response
        CountingResponse counting = new CountingResponse(response);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, counting);
        } finally {
            // 寫在 finally：就算後面丟出例外，這個請求也要留下紀錄——出事的請求最需要被看到
            long elapsedNanos = System.nanoTime() - start;
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            // 請求的大小用 Content-Length（沒帶是 -1）：要自己數就得把 body 讀一遍，那正是不想做的事
            long requestBytes = request.getContentLengthLong();
            counting.flushWriter();
            long responseBytes = counting.bytesWritten();
            int status = counting.getStatus();
            // 用 key-value 而不是只把數字塞進訊息字串：ECS JSON 會把它們變成獨立欄位，
            // Loki 才能寫 `| json | http_response_status_code >= 500` 這種條件，不必用 regex 從句子裡挖。
            // 欄位名照 ECS 的慣例取，event.duration 在 ECS 規定是奈秒，body.bytes 也是 ECS 的欄位
            log.atInfo()
                    .addKeyValue("http.request.method", request.getMethod())
                    .addKeyValue("url.path", request.getRequestURI())
                    .addKeyValue("http.request.body.bytes", requestBytes)
                    .addKeyValue("http.response.status_code", status)
                    .addKeyValue("http.response.body.bytes", responseBytes)
                    .addKeyValue("event.duration", elapsedNanos)
                    // 訊息本身仍然寫成人看得懂的一句：本機開發不開 JSON，終端機上看的就是這句。
                    // 只有路徑，不帶 query string：目前的 query 只有日期，但之後加了搜尋（?q=牙醫）就會把內容帶進來
                    .log("{} {} -> {} ({} ms, {} B in, {} B out)",
                            request.getMethod(), request.getRequestURI(), status, elapsedMillis, requestBytes, responseBytes);
        }
    }

    /** 數 controller 寫了多少 byte，內容照常直接送出去、不留一份。 */
    private static final class CountingResponse extends HttpServletResponseWrapper {

        private long bytes;
        private ServletOutputStream stream;
        private PrintWriter writer;

        CountingResponse(HttpServletResponse response) {
            super(response);
        }

        long bytesWritten() {
            return bytes;
        }

        /**
         * 用 getWriter() 寫的（Spring Security 擋下時的錯誤 JSON 就是）也要數到：把它接到下面會數的 stream 上。
         * 這個 writer 是我們自己包的，容器不知道要沖它，所以記 log 前自己沖一次，不然最後幾個字留在緩衝裡沒送出去。
         */
        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), getCharacterEncoding()));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            flushWriter();
            super.flushBuffer();
        }

        void flushWriter() {
            if (writer != null) {
                writer.flush();
            }
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream target = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        target.write(b);
                        bytes++;
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        target.write(b, off, len);
                        bytes += len;
                    }

                    @Override
                    public boolean isReady() {
                        return target.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        target.setWriteListener(listener);
                    }

                    @Override
                    public void flush() throws IOException {
                        target.flush();
                    }
                };
            }
            return stream;
        }
    }

    /**
     * actuator 不記：Alloy 每 15 秒來抓一次 /actuator/prometheus，
     * 全記下來的話 access log 大半是它，真正的請求反而被淹掉。
     * 健康檢查、指標本身就有 Alloy + Mimir 盯著，不缺這份紀錄。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }
}
