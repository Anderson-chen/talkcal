package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;

/**
 * 用 Google、GitHub 登入之後，找出（或第一次時建立）對應的帳號（inbound port）。
 *
 * 呼叫的時候 provider 那邊已經驗過這個人了（Spring Security 換完 token、驗完 ID token），
 * 這裡只管「他是我們的哪個帳號」，回傳的帳號 ID 就是登入之後的身分，跟帳密登入的一樣。
 *
 * name 是 provider 給的名字（可能是空的），只在第一次建帳號時用來當顯示名稱；空的時候用 fallbackName。
 * 之後再登入不會拿新的名字蓋掉。
 */
public interface SignInExternallyUseCase {

    AccountId signIn(ExternalIdentity identity, String name, String fallbackName);
}
