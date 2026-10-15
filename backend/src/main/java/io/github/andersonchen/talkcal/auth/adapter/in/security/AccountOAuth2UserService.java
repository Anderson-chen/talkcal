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
 * 用 GitHub 登入（純 OAuth2，不是 OIDC）的最後一步。
 *
 * 跟 Google 不一樣的地方：OAuth2 只給 access token，沒有 ID token，「這是誰」要拿 token 另外打 GitHub 的 /user 問
 * （delegate 做的事）。回來的欄位是 GitHub 自己的格式，不是標準：
 * - id：數字，不會變 → 當成使用者 ID。它就是設定裡的 user-name-attribute，所以 user.getName() 拿到的就是它
 * - login：GitHub 帳號名稱，使用者改得掉 → 不能當身分，只在沒填 name 時拿來當顯示名稱
 * - name：使用者自己填的名字，可能是 null
 */
public final class AccountOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
    private final SignInExternallyUseCase signIn;

    public AccountOAuth2UserService(SignInExternallyUseCase signIn) {
        this(new DefaultOAuth2UserService(), signIn);
    }

    /** delegate 是真正去 GitHub 拿使用者資料的那個；測試換成假的，不必連網路。 */
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
