package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 用帳密註冊一個新帳號（inbound port）。只建帳號，不負責登入 —— 登入是 Spring Security 的 filter 的事。
 *
 * 失敗時：
 * - 帳號名稱或密碼不合規則 → IllegalArgumentException（訊息直接給使用者看）
 * - 帳號名稱已經有人用了 → UsernameTakenException
 * - 資料庫出事 → IllegalStateException
 */
public interface RegisterAccountUseCase {

    AccountId register(String username, String password);
}
