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
 * 照前端的方式送請求：保存 cookie；POST、PUT、DELETE 時把 XSRF-TOKEN cookie 的值放進 X-XSRF-TOKEN header
 * （Spring Security 的 csrf.spa() 規定的格式）；登入用表單格式，formLogin 不接受 JSON。
 * 每個 BrowserSession 是一個獨立的瀏覽器，cookie 不共用。
 */
final class BrowserSession {

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    // 關掉就是別的網站騙瀏覽器送請求：cookie 照樣帶，但別的網站讀不到 XSRF-TOKEN
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

    HttpResponse<String> putJson(String path, String json) throws Exception {
        return send(request(path).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json)), true);
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
        // 問助理會等模型，跟 app 的 spring.ai.openai.chat.timeout 一樣給兩分鐘
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofMinutes(2));
    }

    private HttpResponse<String> send(HttpRequest.Builder request, boolean changesSomething) throws Exception {
        if (changesSomething && sendCsrfHeader) {
            // 前端也是這樣做：還沒有 token 就先 GET 一次，後端才會發 XSRF-TOKEN cookie
            if (cookie("XSRF-TOKEN").isEmpty()) {
                get("/api/auth/me");
            }
            cookie("XSRF-TOKEN").ifPresent(token -> request.header("X-XSRF-TOKEN", token));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
