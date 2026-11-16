package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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

    private AccountPrincipals() {
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
