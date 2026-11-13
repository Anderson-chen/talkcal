package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;

import java.util.Objects;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * GitHub 登入（OAuth2，不是 OIDC）的最後一步：把 GitHub 的使用者對到帳號，第一次登入就建一個。
 *
 * OAuth2 沒有 ID token，使用者資料是 delegate 拿 access token 去打 GitHub 的 /user 問來的，欄位是 GitHub 自己的格式：
 * - id：數字、不會變，當成身分。它就是設定裡的 user-name-attribute，所以 user.getName() 拿到的是它
 * - login：帳號名稱，使用者改得掉，不能當身分，只在沒填 name 時當顯示名稱
 * - name：使用者自己填的名字，可能是 null
 */
public final class AccountOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final SignInExternallyUseCase signIn;

    public AccountOAuth2UserService(SignInExternallyUseCase signIn) {
        this(new DefaultOAuth2UserService(), signIn);
    }

    /** 測試傳假的 delegate，不必連 GitHub。 */
    public AccountOAuth2UserService(OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate, SignInExternallyUseCase signIn) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不可為 null");
        this.signIn = Objects.requireNonNull(signIn, "signIn 不可為 null");
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) {
        OAuth2User user = delegate.loadUser(request);
        String registrationId = request.getClientRegistration().getRegistrationId();
        AccountId accountId = ExternalSignIn.run(registrationId, () -> signIn.signIn(
                new ExternalIdentity(ExternalIdentity.Provider.of(registrationId), user.getName()),
                user.getAttribute("name"),
                user.getAttribute("login")));
        String nameAttributeKey = request.getClientRegistration().getProviderDetails()
                .getUserInfoEndpoint().getUserNameAttributeName();
        return AccountPrincipals.oauth2(accountId, user.getAttributes(), nameAttributeKey);
    }
}
