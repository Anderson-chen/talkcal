package io.github.andersonchen.talkcal.app.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * Google、GitHub 這兩個 OAuth 應用程式的設定（#105），從 talkcal.auth.google.*、talkcal.auth.github.* 組出來。
 *
 * 為什麼不用 Spring Boot 現成的 spring.security.oauth2.client.registration.*：
 * - 它的回呼網址預設在 /login/oauth2/code/*，但 nginx 只把 /api 轉給 app，要改到 /api 底下就得每個 provider 都寫一次 redirect-uri
 * - 寫了 redirect-uri 卻沒給 client id，它會在啟動時直接失敗 —— 沒申請 OAuth 應用程式的人連 app 都起不來。
 *   這裡改成「有給 client id 才開那個 provider」，什麼都沒設就跟以前一樣只有帳密登入
 *
 * client secret 只從設定讀（application.properties 再從環境變數接），不寫進程式碼或 repo。
 * 授權網址、token 網址、scope 這些公開的資訊用 Spring Security 內建的 CommonOAuth2Provider，不自己抄一份。
 */
record OAuth2Registrations(List<ClientRegistration> all) {

    /** 回呼網址：{baseUrl} 是使用者瀏覽器看到的網址（經過 vite 或 nginx 之後的那個），在 Google、GitHub 那邊要登記一模一樣的。 */
    static final String REDIRECT_URI = "{baseUrl}/api/auth/oauth2/callback/{registrationId}";
    /** 前端「用 Google 登入」按鈕連到的地方：{registrationId} 換成 google、github。 */
    static final String AUTHORIZATION_BASE_URI = "/api/auth/oauth2/authorization";
    static final String CALLBACK_BASE_URI = "/api/auth/oauth2/callback/*";

    OAuth2Registrations {
        all = List.copyOf(all);
    }

    /** 設定了的 provider；一個都沒設就是空的，這時不開 OAuth2 登入。 */
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
        // 給了 id 卻沒給 secret：多半是環境變數漏設一個。啟動時就失敗，不要等到有人按登入才在 provider 那邊出錯
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
