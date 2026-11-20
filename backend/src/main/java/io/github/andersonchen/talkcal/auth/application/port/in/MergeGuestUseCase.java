package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 訪客登入了另一個帳號：把訪客的行程、對話搬到那個帳號，再刪掉訪客帳號，並記下這次合併：
 * 之後才存進訪客名下的資料由 FinishGuestMergesUseCase 再搬一次。
 * guest 跟 target 是同一個（訪客註冊、升級成免費）或 guest 已經不是訪客時什麼都不做。
 */
public interface MergeGuestUseCase {

    void mergeInto(AccountId guest, AccountId target);
}
