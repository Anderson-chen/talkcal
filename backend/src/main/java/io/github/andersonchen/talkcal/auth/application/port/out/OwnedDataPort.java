package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 帳號名下的行程、對話，包括已經歸檔的。資料在別的模組，auth 只定義要做什麼，由 app.config 設定成呼叫那個模組。
 * 失敗丟 IllegalStateException；中途失敗時已經搬好、刪好的留著，再呼叫一次會處理剩下的。
 */
public interface OwnedDataPort {

    /** from 名下的全部改到 to 名下 */
    void transfer(AccountId from, AccountId to);

    void deleteAll(AccountId owner);
}
