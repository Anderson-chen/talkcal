package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.in.RegisterAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.in.UsernameTakenException;
import io.github.andersonchen.talkcal.auth.application.port.out.HashPasswordPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.util.Objects;

/** 規則都過了才算雜湊：BCrypt 的設計就是算得很慢，不合規的請求不必花這個時間。 */
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
        return saveNew(new Account.PasswordLogin(name, hashPasswordPort.hash(password)));
    }

    @Override
    public AccountId registerFrom(AccountId signedIn, String username, String password) {
        Objects.requireNonNull(signedIn, "signedIn 不可為 null");
        Username name = Username.of(username);
        Account.checkNewPassword(password);

        Account.PasswordLogin login = new Account.PasswordLogin(name, hashPasswordPort.hash(password));
        // 先試著升級：是不是訪客由資料庫在同一句 UPDATE 判斷，不先查再改（另一個分頁可能同時在註冊）
        return switch (saveAccountPort.upgradeGuest(signedIn, login)) {
            case UPGRADED -> signedIn;
            case USERNAME_TAKEN -> throw new UsernameTakenException(name);
            case NOT_A_GUEST -> saveNew(login);
        };
    }

    private AccountId saveNew(Account.PasswordLogin login) {
        Account account = Account.withPassword(AccountId.newId(), login.username(), login.passwordHash());
        if (!saveAccountPort.saveIfUsernameFree(account)) {
            throw new UsernameTakenException(login.username());
        }
        return account.id();
    }
}
