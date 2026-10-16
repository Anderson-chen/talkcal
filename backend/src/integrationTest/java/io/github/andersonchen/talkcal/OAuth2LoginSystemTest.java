package io.github.andersonchen.talkcal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

/**
 * 設了 Google、GitHub 的 client id 之後，app 這一側接起來的樣子（#105）。
 *
 * 測不到的部分：真的到 Google 登入、Google 轉回來帶著 code、拿 code 換 token —— 那要真的 OAuth 應用程式和真人按同意。
 * 那一段之後「對帳號」的邏輯由 ExternalUserServicesTest、JdbcAccountAdapterTest 測；這裡測的是頭尾：
 * 按鈕連過去會轉到對的地方（回呼網址、scope、state 都對），以及轉回來的東西不對時會被擋。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "talkcal.auth.google.client-id=google-client-id",
        "talkcal.auth.google.client-secret=google-client-secret",
        "talkcal.auth.github.client-id=github-client-id",
        "talkcal.auth.github.client-secret=github-client-secret"})
@DisplayName("系統測試：Google、GitHub 登入")
class OAuth2LoginSystemTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    private BrowserSession browser() {
        return new BrowserSession(port);
    }

    private static Map<String, String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("登入頁問得到開了哪些登入方式（沒登入也能問）")
    void providersAreListed() throws Exception {
        HttpResponse<String> response = browser().get("/api/auth/providers");

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("[\"google\",\"github\"]", JSON.readTree(response.body()).at("/providers").toString());
    }

    @Test
    @DisplayName("按「用 Google 登入」：轉到 Google，帶著我們的 client id、/api 底下的回呼網址、openid scope 和 state")
    void googleAuthorizationRedirect() throws Exception {
        HttpResponse<String> response = browser().get("/api/auth/oauth2/authorization/google");

        assertEquals(302, response.statusCode());
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertEquals("accounts.google.com", location.getHost());
        Map<String, String> params = query(location);
        assertEquals("google-client-id", params.get("client_id"));
        assertEquals("http://localhost:" + port + "/api/auth/oauth2/callback/google", params.get("redirect_uri"),
                "回呼網址要在 /api 底下（nginx 只轉 /api），而且跟 Google 那邊登記的一模一樣");
        assertTrue(params.get("scope").contains("openid"), params.get("scope"));
        assertTrue(!params.getOrDefault("state", "").isBlank(), "state 防 CSRF：轉回來時要對得上");
        assertTrue(!location.toString().contains("google-client-secret"), "secret 只在後端換 token 時用，不會出現在網址上");
    }

    @Test
    @DisplayName("#127 雲端前面有 Caddy 解 HTTPS：nginx 帶著 X-Forwarded-Proto: https 轉過來，回呼網址要是 https://（反例：上面沒帶的那題還是 http://）")
    void callbackUsesSchemeFromProxy() throws Exception {
        // 照雲端上 nginx 轉給 app 的樣子送：app 自己收到的是 http，原本的 scheme 在 header 裡
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/oauth2/authorization/google"))
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-For", "203.0.113.7")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(302, response.statusCode());
        String redirectUri = query(URI.create(response.headers().firstValue("Location").orElseThrow())).get("redirect_uri");
        assertTrue(redirectUri.startsWith("https://localhost"), redirectUri);
        assertTrue(redirectUri.endsWith("/api/auth/oauth2/callback/google"), redirectUri);
    }

    @Test
    @DisplayName("按「用 GitHub 登入」：轉到 GitHub")
    void githubAuthorizationRedirect() throws Exception {
        HttpResponse<String> response = browser().get("/api/auth/oauth2/authorization/github");

        assertEquals(302, response.statusCode());
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertEquals("github.com", location.getHost());
        assertEquals("http://localhost:" + port + "/api/auth/oauth2/callback/github", query(location).get("redirect_uri"));
    }

    @Test
    @DisplayName("轉回來的 state 對不上（別人偽造的回呼、或使用者在 Google 按了取消）：不登入，轉回首頁帶著錯誤")
    void forgedCallbackIsRejected() throws Exception {
        BrowserSession amy = browser();
        amy.get("/api/auth/oauth2/authorization/google");

        HttpResponse<String> forged = amy.get("/api/auth/oauth2/callback/google?code=stolen-code&state=forged-state");
        HttpResponse<String> cancelled = amy.get("/api/auth/oauth2/callback/google?error=access_denied&state=forged-state");

        assertEquals(302, forged.statusCode());
        // 轉回同一個網址的首頁（Tomcat 會補成完整網址，host 是請求進來時的那個）
        assertEquals("http://localhost:" + port + "/?login_error=oauth", forged.headers().firstValue("Location").orElseThrow());
        assertEquals("http://localhost:" + port + "/?login_error=oauth", cancelled.headers().firstValue("Location").orElseThrow());
        assertEquals(401, amy.get("/api/auth/me").statusCode());
    }

    @Test
    @DisplayName("反例：開了 OAuth2 登入，沒登入的 API 請求照樣是 401 JSON，不會被轉址到 Google")
    void apiStillAnswers401() throws Exception {
        HttpResponse<String> response = browser().get("/api/calendar/events?from=2030-01-01&to=2030-02-01");

        assertEquals(401, response.statusCode());
        assertEquals("請先登入", JSON.readTree(response.body()).at("/error").asString());
    }

    @Test
    @DisplayName("反例：沒設定的 provider 不能登入")
    void unknownProviderIsNotFound() throws Exception {
        HttpResponse<String> response = browser().get("/api/auth/oauth2/authorization/facebook");

        assertTrue(response.statusCode() >= 400, "不是轉址到某個 provider：" + response.statusCode());
    }
}
