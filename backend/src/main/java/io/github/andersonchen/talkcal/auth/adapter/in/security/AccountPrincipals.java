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
 * 登入之後 Spring Security 記在 session 裡的「目前是誰」。三種登入方式都要長得一樣：
 * getName() 是我們的帳號 ID（不是 Google 的 sub、不是 GitHub 的 id），權限一律是 USER。
 * 這樣 /api/auth/me、之後的授權（#104）只認帳號 ID，不必知道這個人是怎麼登入的。
 */
public final class AccountPrincipals {

    /** 登入的人都有的權限。目前只有一種使用者，不分角色。 */
    public static final String AUTHORITY = "USER";

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority(AUTHORITY));

    private AccountPrincipals() {
    }

    /** 用 Google（OIDC）登入的人：留著 ID token 和使用者資料，只把名字換成帳號 ID。 */
    static OidcUser oidc(AccountId accountId, OidcUser user) {
        return new AccountOidcUser(accountId, user);
    }

    /** 用 GitHub（OAuth2）登入的人：留著 GitHub 給的使用者資料，只把名字換成帳號 ID。 */
    static DefaultOAuth2User oauth2(AccountId accountId, Map<String, Object> attributes, String nameAttributeKey) {
        return new AccountOAuth2User(accountId, attributes, nameAttributeKey);
    }

    // 要放進 session，所以跟父類別一樣是 Serializable；帳號 ID 存成字串，不讓 session 依賴 domain 的型別
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
