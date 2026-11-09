package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;

/**
 * 刪掉其他模組裡屬於這個帳號的資料（outbound port）。
 *
 * auth 不知道帳號被拿去擁有了什麼（行程、對話在 calendar），也不該知道（模組互不認識）。
 * 它只知道「刪帳號之前，要先請擁有資料的那些模組把這個人忘掉」；接到哪個模組是 app.config 的事。
 * 之後多一個模組存了帳號的資料，就在 app.config 多接一個，這裡不必改。
 *
 * 要能重複呼叫：已經沒資料的帳號再呼叫一次，照樣成功。失敗時丟 IllegalStateException。
 */
public interface ForgetAccountDataPort {

    void forget(AccountId id);
}
