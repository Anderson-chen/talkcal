package io.github.andersonchen.talkcal.auth.adapter.in.security;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

import java.util.function.Supplier;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Google、GitHub 對帳號時共用的步驟：取得轉去 provider 之前登入的帳號，對帳號時出的錯一律轉成 OAuth2AuthenticationException。
 *
 * 錯誤要轉：Spring Security 只把 AuthenticationException 當成登入失敗、交給 failure handler 轉回登入頁；
 * 其他例外（資料庫無法連線、provider 沒給 ID）會一路丟成 500 的錯誤頁，使用者停在看不懂的網址上。
 */
final class ExternalSignIn {

    private ExternalSignIn() {
    }

    /**
     * 轉去 provider 之前這個瀏覽器登入的帳號（例如訪客），沒有是 null。
     * provider 轉回來的這個請求還沒換成新的登入狀態，SecurityContextHolder 裡還是 session 原本的那個
     */
    static AccountId signedInBefore() {
        return AccountPrincipals.signedInAccount().orElse(null);
    }

    static AccountId run(String registrationId, Supplier<AccountId> signIn) {
        try {
            return signIn.get();
        } catch (RuntimeException e) {
            // 錯誤碼和訊息只進 log（failure handler 記的），不給使用者看
            throw new OAuth2AuthenticationException(new OAuth2Error("account_unavailable"),
                    registrationId + " 登入成功，但對不到帳號：" + e.getMessage(), e);
        }
    }
}
