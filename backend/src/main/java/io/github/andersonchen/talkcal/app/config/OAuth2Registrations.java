package io.github.andersonchen.talkcal.app.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * Google、GitHub 的 OAuth client 設定，從 talkcal.auth.google.*、talkcal.auth.github.* 組出來。
 *
 * 不用 Spring Boot 的 spring.security.oauth2.client.registration.*：
 * - 回呼網址預設在 /login/oauth2/code/*，nginx 只把 /api 轉給 app，每個 provider 都得另寫 redirect-uri
 * - 寫了 redirect-uri 卻沒給 client id，啟動就失敗，沒申請 OAuth 應用程式的人連 app 都無法啟動
 * 這裡只開有 client id 的 provider，都沒設就只有帳密登入。
 */
record OAuth2Registrations(List<ClientRegistration> all) {

    /** {baseUrl} 是瀏覽器看到的網址（經過 vite 或 nginx 之後的那個）；Google、GitHub 那邊要登記一模一樣的回呼網址。 */
    static final String REDIRECT_URI = "{baseUrl}/api/auth/oauth2/callback/{registrationId}";
    /** 前端的登入按鈕寫死這個路徑（web/src/auth.ts），改了要一起改。 */
    static final String AUTHORIZATION_BASE_URI = "/api/auth/oauth2/authorization";
    static final String CALLBACK_BASE_URI = "/api/auth/oauth2/callback/*";

    OAuth2Registrations {
        all = List.copyOf(all);
    }

    static OAuth2Registrations of(String googleClientId, String googleClientSecret,
                                  String githubClientId, String githubClientSecret) {
        List<ClientRegistration> registrations = new ArrayList<>();
        registration(CommonOAuth2Provider.GOOGLE, "google", googleClientId, googleClientSecret).ifPresent(registrations::add);
        registration(CommonOAuth2Provider.GITHUB, "github", githubClientId, githubClientSecret).ifPresent(registrations::add);
        return new OAuth2Registrations(registrations);
    }

    private static Optional<ClientRegistration> registration(CommonOAuth2Provider provider, String id,
                                                             String clientId, String clientSecret) {
        if (isBlank(clientId)) {
            return Optional.empty();
        }
        // 有 id 沒 secret 多半是漏設一個環境變數：啟動就失敗，不要等到有人按登入才在 provider 那邊出錯
        if (isBlank(clientSecret)) {
            throw new IllegalStateException("talkcal.auth." + id + ".client-id 有設定，但 client-secret 沒有（環境變數 "
                    + id.toUpperCase() + "_CLIENT_SECRET）");
        }
        return Optional.of(provider.getBuilder(id)
                .clientId(clientId.strip())
                .clientSecret(clientSecret.strip())
                .redirectUri(REDIRECT_URI)
                .build());
    }

    boolean isEmpty() {
        return all.isEmpty();
    }

    List<String> ids() {
        return all.stream().map(ClientRegistration::getRegistrationId).toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
