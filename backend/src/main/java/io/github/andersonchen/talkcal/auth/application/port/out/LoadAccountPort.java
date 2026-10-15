package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;

import java.util.Optional;

/** 讀帳號。查不到回 empty；資料庫出事丟 IllegalStateException。 */
public interface LoadAccountPort {

    Optional<Account> load(Username username);

    Optional<Account> load(AccountId id);

    /** 這個 Google、GitHub 使用者對到的帳號；還沒用它登入過就是 empty。 */
    Optional<Account> load(ExternalIdentity identity);
}
