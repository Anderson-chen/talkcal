package io.github.andersonchen.talkcal;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 系統測試用的「瀏覽器」：照前端的方式跟後端講話。
 *
 * - 記得 cookie（session、XSRF-TOKEN），每個請求自動帶上 —— 瀏覽器本來就會這樣
 * - 送會改東西的請求（POST、DELETE）時，從 XSRF-TOKEN cookie 讀出 token、放進 X-XSRF-TOKEN header ——
 *   這是前端要自己做的那一步（Spring Security 的 csrf.spa() 規定的格式）
 * - 登入用表單格式送 username、password（Spring Security 的 formLogin 吃的是表單，不是 JSON）
 *
 * 每個 BrowserSession 是一個獨立的瀏覽器：兩個 session 就像兩個人，cookie 不互通。
 */
final class BrowserSession {

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    // 想模擬「別的網站騙瀏覽器送請求」時關掉：cookie 照樣帶，但讀不到我們的 XSRF-TOKEN、送不出 header
    private boolean sendCsrfHeader = true;

    BrowserSession(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    BrowserSession withoutCsrfHeader() {
        sendCsrfHeader = false;
        return this;
    }

    HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET(), false);
    }

    HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)), true);
    }

    HttpResponse<String> delete(String path) throws Exception {
        return send(request(path).DELETE(), true);
    }

    HttpResponse<String> register(String username, String password) throws Exception {
        return postJson("/api/auth/register", """
                {"username": "%s", "password": "%s"}""".formatted(username, password));
    }

    HttpResponse<String> login(String username, String password) throws Exception {
        String form = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return send(request("/api/auth/login")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)), true);
    }

    HttpResponse<String> logout() throws Exception {
        return send(request("/api/auth/logout").POST(HttpRequest.BodyPublishers.noBody()), true);
    }

    /** 註冊再登入，回到「已登入」的狀態。 */
    BrowserSession signUpAndLogIn(String username, String password) throws Exception {
        var registered = register(username, password);
        if (registered.statusCode() != 201) {
            throw new IllegalStateException("註冊失敗：" + registered.statusCode() + " " + registered.body());
        }
        var loggedIn = login(username, password);
        if (loggedIn.statusCode() != 204) {
            throw new IllegalStateException("登入失敗：" + loggedIn.statusCode() + " " + loggedIn.body());
        }
        return this;
    }

    Optional<String> cookie(String name) {
        return cookies.getCookieStore().get(URI.create(baseUrl)).stream()
                .filter(c -> c.getName().equals(name)).map(HttpCookie::getValue).findFirst();
    }

    private HttpRequest.Builder request(String path) {
        // 助理那一題會等模型，給到跟 app 呼叫模型一樣的兩分鐘
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofMinutes(2));
    }

    private HttpResponse<String> send(HttpRequest.Builder request, boolean changesSomething) throws Exception {
        if (changesSomething && sendCsrfHeader) {
            // 跟前端一樣：手上沒有 token 就先 GET 一次，讓後端發 XSRF-TOKEN cookie 過來
            if (cookie("XSRF-TOKEN").isEmpty()) {
                get("/api/auth/me");
            }
            cookie("XSRF-TOKEN").ifPresent(token -> request.header("X-XSRF-TOKEN", token));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
