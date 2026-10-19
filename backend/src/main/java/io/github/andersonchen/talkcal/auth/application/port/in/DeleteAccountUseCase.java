package io.github.andersonchen.talkcal.auth.application.port.in;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 刪除帳號（inbound port）：使用者要求刪除時，帳號、它的登入方式，以及其他模組裡屬於它的資料全部刪掉（#52）。
 *
 * 只刪得到自己的：呼叫端（HTTP controller）傳進來的一定是登入者自己的 id，不收請求裡帶的 id。
 * 重複呼叫沒關係：帳號已經不在了也照樣把資料再刪一次、照樣成功 —— 中途失敗時重試就會補完。
 *
 * 失敗時：id 是 null → IllegalArgumentException；資料庫出事 → IllegalStateException。
 */
public interface DeleteAccountUseCase {

    void delete(AccountId id);
}
