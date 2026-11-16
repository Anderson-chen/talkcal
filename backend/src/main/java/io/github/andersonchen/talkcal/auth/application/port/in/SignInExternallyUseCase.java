package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;

/**
 * 呼叫時 provider 已經驗過這個人（Spring Security 換完 token、驗完 ID token），這裡只找出或建立對應的帳號；
 * 回傳的帳號 ID 就是登入之後的身分，跟帳密登入的一樣。
 *
 * name 只在第一次建帳號時當顯示名稱，空的時候用 fallbackName；之後再登入不拿新的名字蓋掉。
 */
public interface SignInExternallyUseCase {

    AccountId signIn(ExternalIdentity identity, String name, String fallbackName);
}
