package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.in.FindAccountUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;

import java.util.Objects;
import java.util.Optional;

/** 實作「查帳號」。 */
public final class FindAccountService implements FindAccountUseCase {

    private final LoadAccountPort loadAccountPort;

    public FindAccountService(LoadAccountPort loadAccountPort) {
        this.loadAccountPort = Objects.requireNonNull(loadAccountPort, "loadAccountPort 不可為 null");
    }

    @Override
    public Optional<Account> byUsername(String username) {
        Username name;
        try {
            name = Username.of(username);
        } catch (IllegalArgumentException e) {
            // 不合規則的名字不可能註冊過，就是查不到；不丟錯，登入失敗時才不會透露是哪一欄錯
            return Optional.empty();
        }
        return loadAccountPort.load(name);
    }

    @Override
    public Optional<Account> byId(AccountId id) {
        Objects.requireNonNull(id, "帳號 ID 不可為 null");
        return loadAccountPort.load(id);
    }
}
