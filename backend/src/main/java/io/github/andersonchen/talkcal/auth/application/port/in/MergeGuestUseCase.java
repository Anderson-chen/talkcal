package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 訪客登入了另一個帳號：先刪掉訪客帳號、記下這次合併，再把訪客的行程、對話搬到那個帳號。
 * 記下之後，這次沒搬完的和之後才存進訪客名下的，由 FinishGuestMergesUseCase 搬完，不靠 session 和訪客 cookie。
 * guest 跟 target 是同一個（訪客註冊、升級成免費）或 guest 已經不是訪客時什麼都不做。
 *
 * 記下合併之前就失敗（資料庫出錯）丟 IllegalStateException，什麼都沒改；記下之後搬資料失敗丟 MergePendingException。
 */
public interface MergeGuestUseCase {

    void mergeInto(AccountId guest, AccountId target);
}
