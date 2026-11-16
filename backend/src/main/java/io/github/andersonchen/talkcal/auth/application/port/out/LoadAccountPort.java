package io.github.andersonchen.talkcal.auth.application.port.out;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;

import java.util.Optional;

/** 查不到回 empty；資料庫出錯丟 IllegalStateException。 */
public interface LoadAccountPort {

    Optional<Account> load(Username username);

    Optional<Account> load(AccountId id);

    Optional<Account> load(ExternalIdentity identity);
}
