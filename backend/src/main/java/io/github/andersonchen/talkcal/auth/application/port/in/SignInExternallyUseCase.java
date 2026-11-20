package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;

/**
 * 呼叫時 provider 已經驗過這個人（Spring Security 換完 token、驗完 ID token），這裡只找出或建立對應的帳號；
 * 回傳的帳號 ID 就是登入之後的身分，跟帳密登入的一樣。
 *
 * name 只在第一次建帳號時當顯示名稱，空的時候用 fallbackName；之後再登入不拿新的名字蓋掉。
 *
 * signedIn 是轉去 provider 之前這個瀏覽器登入的帳號，沒登入是 null。identity 還沒有帳號、signedIn 又是訪客時，
 * identity 對到那個訪客帳號、帳號升級成免費會員，帳號 ID 不變，名下的行程和對話不用搬（跟帳密註冊一樣）。
 * 其他情況不看 signedIn。
 */
public interface SignInExternallyUseCase {

    AccountId signIn(AccountId signedIn, ExternalIdentity identity, String name, String fallbackName);
}
