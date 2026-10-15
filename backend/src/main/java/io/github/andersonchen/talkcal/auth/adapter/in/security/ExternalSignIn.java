package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.util.function.Supplier;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Google、GitHub 共用的一段：對帳號時出的錯，一律翻成 OAuth2AuthenticationException。
 *
 * Spring Security 只把 AuthenticationException 當成「登入失敗」交給 failure handler（轉回登入頁、顯示訊息）；
 * 其他例外（資料庫掛了、provider 沒給 ID）會一路丟出去變成 500 的錯誤頁，使用者停在一個看不懂的網址上。
 */
final class ExternalSignIn {

    private ExternalSignIn() {
    }

    static AccountId run(String registrationId, Supplier<AccountId> signIn) {
        try {
            return signIn.get();
        } catch (RuntimeException e) {
            // 錯誤碼會進 log（failure handler 記的），訊息不會給使用者看到
            throw new OAuth2AuthenticationException(new OAuth2Error("account_unavailable"),
                    registrationId + " 登入成功，但對不到帳號：" + e.getMessage(), e);
        }
    }
}
