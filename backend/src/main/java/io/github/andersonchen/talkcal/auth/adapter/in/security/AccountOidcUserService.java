package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;

import java.util.Objects;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * 用 Google 登入（OIDC）的最後一步：Spring Security 已經換到 token、驗過 ID token 的簽章和對象，
 * 這裡把「Google 的這個人」對到我們的帳號（第一次登入就建一個），回傳記在 session 裡的身分。
 *
 * OIDC 的使用者 ID（sub）在 ID token 裡，是標準欄位，不必像 GitHub 那樣另外打 API 去拿。
 */
public final class AccountOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final SignInExternallyUseCase signIn;

    public AccountOidcUserService(SignInExternallyUseCase signIn) {
        this(new OidcUserService(), signIn);
    }

    /** delegate 是真正去 Google 拿使用者資料的那個；測試換成假的，不必連網路。 */
    public AccountOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, SignInExternallyUseCase signIn) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不可為 null");
        this.signIn = Objects.requireNonNull(signIn, "signIn 不可為 null");
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = delegate.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();
        AccountId accountId = ExternalSignIn.run(registrationId, () -> signIn.signIn(
                new ExternalIdentity(ExternalIdentity.Provider.of(registrationId), user.getSubject()),
                // 名字是 profile scope 給的；使用者沒給名字時用 email（email scope），只有他自己看得到
                user.getFullName(),
                user.getEmail()));
        return AccountPrincipals.oidc(accountId, user);
    }
}
