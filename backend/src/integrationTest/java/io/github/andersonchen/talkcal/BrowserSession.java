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
    // 關掉就是模擬別的網站讓瀏覽器送出請求：cookie 照樣帶，但別的網站讀不到 XSRF-TOKEN
    private boolean sendCsrfHeader = true;
    // 扮成從另一個 IP 來的使用者。測試的 client 一律從 127.0.0.1 連，換不了來源位址，只能靠這個 header：
    // app 設了 server.forward-headers-strategy=native，Tomcat 把 127.0.0.1 當成內部的 proxy，照 X-Forwarded-For 還原 remoteAddr，
    // 跟線上經過 nginx 時是同一條路。null 是不帶，來源就是 127.0.0.1
    private String forwardedFor;

    BrowserSession(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    BrowserSession withoutCsrfHeader() {
        sendCsrfHeader = false;
        return this;
    }

    BrowserSession forwardedFor(String header) {
        forwardedFor = header;
        return this;
    }

    HttpResponse<String> get(String path) throws Exception {
        return send(request(path).GET(), false);
    }

    HttpResponse<String> postJson(String path, String json) throws Exception {
        return send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)), true);
    }

    /** 帶 Idempotency-Key 送，重送時帶同一把（前端的 submitOnce.ts 也是這樣） */
    HttpResponse<String> postJson(String path, String json, String idempotencyKey) throws Exception {
        return send(request(path).header("Content-Type", "application/json").header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(json)), true);
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
        return cookie(name, "/");
    }

    /** path 設在某個路徑底下的 cookie（例如訪客 cookie 只送到 /api），要用那個路徑查才查得到 */
    Optional<String> cookie(String name, String path) {
        return cookies.getCookieStore().get(URI.create(baseUrl + path)).stream()
                .filter(c -> c.getName().equals(name)).map(HttpCookie::getValue).findFirst();
    }

    /** 模擬瀏覽器關掉再開：session cookie 沒了，只剩這個留得比較久的 cookie */
    BrowserSession withCookie(String name, String value, String path) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setPath(path);
        cookie.setVersion(0);
        cookies.getCookieStore().add(URI.create(baseUrl + path), cookie);
        return this;
    }

    private HttpRequest.Builder request(String path) {
        // 問助理會等模型，不能比 app 的 spring.ai.openai.chat.timeout 短，改設定時這裡一起改
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofMinutes(2));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return builder;
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
