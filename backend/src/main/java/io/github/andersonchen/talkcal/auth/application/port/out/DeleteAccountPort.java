package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 刪掉帳號本身和它的登入方式（Google、GitHub 的對應）。回傳有沒有真的刪到；資料庫出事丟 IllegalStateException。
 */
public interface DeleteAccountPort {

    boolean delete(AccountId id);
}
