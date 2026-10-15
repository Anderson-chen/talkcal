package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.in.RegisterAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.UsernameTakenException;
import io.github.andersonchen.talkcal.auth.application.port.out.HashPasswordPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.util.Objects;

/**
 * 實作「註冊」：先檢查規則，規則都過了才算雜湊（BCrypt 故意算得很慢，不合規的請求不必浪費這個時間），再存。
 */
public final class RegisterAccountService implements RegisterAccountUseCase {

    private final SaveAccountPort saveAccountPort;
    private final HashPasswordPort hashPasswordPort;

    public RegisterAccountService(SaveAccountPort saveAccountPort, HashPasswordPort hashPasswordPort) {
        this.saveAccountPort = Objects.requireNonNull(saveAccountPort, "saveAccountPort 不可為 null");
        this.hashPasswordPort = Objects.requireNonNull(hashPasswordPort, "hashPasswordPort 不可為 null");
    }

    @Override
    public AccountId register(String username, String password) {
        Username name = Username.of(username);
        Account.checkNewPassword(password);

        Account account = new Account(AccountId.newId(), name, hashPasswordPort.hash(password));
        if (!saveAccountPort.saveIfUsernameFree(account)) {
            throw new UsernameTakenException(name);
        }
        return account.id();
    }
}
