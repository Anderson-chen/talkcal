package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;

import java.util.Optional;

/** 讀帳號。查不到回 empty；資料庫出事丟 IllegalStateException。 */
public interface LoadAccountPort {

    Optional<Account> load(Username username);

    Optional<Account> load(AccountId id);
}
