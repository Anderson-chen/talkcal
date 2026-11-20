package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 只建帳號，不登入：登入是 Spring Security 的 filter 的事。
 *
 * 失敗時：
 * - 帳號名稱或密碼不合規則 → IllegalArgumentException（訊息直接給使用者看）
 * - 帳號名稱已經有人用了 → UsernameTakenException
 * - 資料庫出錯 → IllegalStateException
 */
public interface RegisterAccountUseCase {

    AccountId register(String username, String password);

    /**
     * 已經登入的人註冊：登入的是訪客，就在同一個帳號上補帳號名稱和密碼、改成免費會員，帳號 ID 不變，
     * 名下的行程和對話不用搬；不是訪客（會員、或另一個分頁已經先註冊好了）就跟 register 一樣開一個新帳號。
     * 回傳註冊好的帳號 ID：等於 signedIn 就是訪客升級的。失敗跟 register 一樣。
     */
    AccountId registerFrom(AccountId signedIn, String username, String password);
}
