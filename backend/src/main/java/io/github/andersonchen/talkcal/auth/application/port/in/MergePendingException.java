package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 訪客的合併已經記下、資料這次沒搬完：剩下的由 FinishGuestMergesUseCase 搬完，呼叫端不必再呼叫 MergeGuestUseCase。
 * 合併還沒記下就失敗時丟的是一般的 IllegalStateException，呼叫端要先 catch 這一個。
 */
public class MergePendingException extends IllegalStateException {

    public MergePendingException(AccountId guest, AccountId target, IllegalStateException cause) {
        super("訪客 " + guest + " 併進 " + target + " 的合併已經記下，資料這次沒搬完", cause);
    }
}
