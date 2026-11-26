package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * 登入之後記在 session 裡的「目前是誰」。三種登入方式的格式都相同：getName() 是帳號 ID（不是 Google 的 sub、GitHub 的 id），
 * 權限一律是 USER。/api/auth/me、比對資料擁有者時就只比對帳號 ID，不分登入方式。
 */
public final class AccountPrincipals {

    public static final String AUTHORITY = "USER";

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority(AUTHORITY));

    private static final AuthenticationTrustResolver TRUST = new AuthenticationTrustResolverImpl();

    private AccountPrincipals() {
    }

    /** 真的登入了：不是 null、不是匿名（Spring Security 對沒登入的請求放的是匿名的 Authentication） */
    static boolean signedIn(Authentication authentication) {
        return TRUST.isAuthenticated(authentication);
    }

    /** 這個請求目前登入的帳號，從 SecurityContextHolder 讀；沒登入、或名字不是帳號 ID 時是 empty */
    static Optional<AccountId> signedInAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!signedIn(authentication)) {
            return Optional.empty();
        }
        try {
            return Optional.of(AccountId.of(authentication.getName()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 訪客用 cookie 或按「以訪客身分試用」登入時放進 session 的：沒有密碼可比，直接是登入狀態 */
    static Authentication guest(AccountId accountId) {
        return UsernamePasswordAuthenticationToken.authenticated(accountId.toString(), null, AUTHORITIES);
    }

    static OidcUser oidc(AccountId accountId, OidcUser user) {
        return new AccountOidcUser(accountId, user);
    }

    static DefaultOAuth2User oauth2(AccountId accountId, Map<String, Object> attributes, String nameAttributeKey) {
        return new AccountOAuth2User(accountId, attributes, nameAttributeKey);
    }

    // 要放進 session，所以要 Serializable（父類別已經是）；帳號 ID 存成字串，session 才不依賴 domain 的型別
    private static final class AccountOidcUser extends DefaultOidcUser {

        private final String accountId;

        AccountOidcUser(AccountId accountId, OidcUser user) {
            super(AUTHORITIES, user.getIdToken(), user.getUserInfo());
            this.accountId = accountId.toString();
        }

        @Override
        public String getName() {
            return accountId;
        }
    }

    private static final class AccountOAuth2User extends DefaultOAuth2User {

        private final String accountId;

        AccountOAuth2User(AccountId accountId, Map<String, Object> attributes, String nameAttributeKey) {
            super(AUTHORITIES, attributes, nameAttributeKey);
            this.accountId = accountId.toString();
        }

        @Override
        public String getName() {
            return accountId;
        }
    }
}
