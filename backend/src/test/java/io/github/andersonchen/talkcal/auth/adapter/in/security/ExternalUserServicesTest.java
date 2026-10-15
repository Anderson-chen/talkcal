package io.github.andersonchen.talkcal.auth.adapter.in.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Google、GitHub 登入的最後一步：provider 給的使用者資料怎麼對到帳號、session 裡記的身分長怎樣。
 * 去 provider 拿資料的那段（delegate）換成假的，不連網路。
 */
@DisplayName("Google、GitHub 的使用者 → 帳號")
class ExternalUserServicesTest {

    private static final AccountId ACCOUNT = AccountId.newId();
    private static final Instant NOW = Instant.now();
    private static final OAuth2AccessToken ACCESS_TOKEN =
            new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access-token", NOW, NOW.plusSeconds(60));

    /** 記下被叫的參數，回固定的帳號 ID */
    private record SignInCall(ExternalIdentity identity, String name, String fallbackName) {
    }

    private final List<SignInCall> calls = new ArrayList<>();
    private final SignInExternallyUseCase signIn = (identity, name, fallbackName) -> {
        calls.add(new SignInCall(identity, name, fallbackName));
        return ACCOUNT;
    };

    private static ClientRegistration registration(CommonOAuth2Provider provider, String id) {
        return provider.getBuilder(id).clientId("client-id").clientSecret("client-secret").build();
    }

    private static OidcUserRequest googleRequest(OidcIdToken idToken) {
        return new OidcUserRequest(registration(CommonOAuth2Provider.GOOGLE, "google"), ACCESS_TOKEN, idToken);
    }

    private static OidcIdToken idToken(Map<String, Object> claims) {
        return OidcIdToken.withTokenValue("id-token").issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).claims(c -> c.putAll(claims)).build();
    }

    @Test
    @DisplayName("Google：用 ID token 的 sub 對帳號，名字用 name、沒有時用 email；session 裡的名字是我們的帳號 ID")
    void google() {
        OidcIdToken token = idToken(Map.of("sub", "google-sub-1", "name", "Amy Chen", "email", "amy@example.com"));
        var service = new AccountOidcUserService(request -> new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, request.getIdToken()), signIn);

        OidcUser user = service.loadUser(googleRequest(token));

        assertEquals(List.of(new SignInCall(new ExternalIdentity(ExternalIdentity.Provider.GOOGLE, "google-sub-1"),
                "Amy Chen", "amy@example.com")), calls);
        assertEquals(ACCOUNT.toString(), user.getName(), "跟帳密登入一樣，Authentication.getName() 是帳號 ID");
        assertEquals("google-sub-1", user.getSubject(), "Google 給的資料照樣留著");
        assertEquals(List.of(AccountPrincipals.AUTHORITY), user.getAuthorities().stream().map(Object::toString).toList());
    }

    @Test
    @DisplayName("GitHub：用數字 id 對帳號（不是 login，login 改得掉），名字用 name、沒填時用 login")
    void github() {
        // GitHub 的 /user 回的是自己的格式：id 是數字、name 可能是 null
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("id", 583231);
        attributes.put("login", "amy-gh");
        attributes.put("name", null);
        ClientRegistration github = registration(CommonOAuth2Provider.GITHUB, "github");
        var service = new AccountOAuth2UserService(
                request -> new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, attributes, "id"), signIn);

        OAuth2User user = service.loadUser(new OAuth2UserRequest(github, ACCESS_TOKEN));

        assertEquals(List.of(new SignInCall(new ExternalIdentity(ExternalIdentity.Provider.GITHUB, "583231"),
                null, "amy-gh")), calls);
        assertEquals(ACCOUNT.toString(), user.getName());
        assertEquals("amy-gh", user.getAttribute("login"));
    }

    @Test
    @DisplayName("對帳號時出錯（資料庫掛了）：翻成 OAuth2AuthenticationException，交給 failure handler 轉回登入頁，不是 500")
    void failureBecomesAuthenticationFailure() {
        SignInExternallyUseCase broken = (identity, name, fallbackName) -> {
            throw new IllegalStateException("讀取帳號失敗");
        };
        var service = new AccountOidcUserService(request -> new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, request.getIdToken()), broken);

        var e = assertThrows(OAuth2AuthenticationException.class,
                () -> service.loadUser(googleRequest(idToken(Map.of("sub", "google-sub-1")))));
        assertEquals("account_unavailable", e.getError().getErrorCode());
    }

    @Test
    @DisplayName("設定裡多了一個沒寫對應方式的 provider：一樣是登入失敗，不會建出帳號")
    void unknownProvider() {
        // 網址借 GitHub 的就好，這裡只看 registrationId
        ClientRegistration okta = registration(CommonOAuth2Provider.GITHUB, "okta");
        Map<String, Object> attributes = Map.of("sub", "okta-1");
        var service = new AccountOAuth2UserService(
                request -> new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, attributes, "sub"), signIn);

        assertThrows(OAuth2AuthenticationException.class, () -> service.loadUser(new OAuth2UserRequest(okta, ACCESS_TOKEN)));
        assertEquals(List.of(), calls);
    }
}
