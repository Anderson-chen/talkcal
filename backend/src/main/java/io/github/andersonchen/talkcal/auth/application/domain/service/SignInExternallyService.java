package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.port.in.SignInExternallyUseCase;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.util.Objects;

/**
 * 實作「用 Google、GitHub 登入」：登入過就用原本的帳號，沒有就建一個新的。
 *
 * 不靠 email 去找既有的帳號合併：見 V10 的 migration，那等於讓填了你 email 的人登進你的帳號。
 * 代價是同一個人用 Google 和 GitHub 登入會是兩個帳號，要合併得等之後做「已登入時綁定」。
 */
public final class SignInExternallyService implements SignInExternallyUseCase {

    private final LoadAccountPort loadAccountPort;
    private final SaveAccountPort saveAccountPort;

    public SignInExternallyService(LoadAccountPort loadAccountPort, SaveAccountPort saveAccountPort) {
        this.loadAccountPort = Objects.requireNonNull(loadAccountPort, "loadAccountPort 不可為 null");
        this.saveAccountPort = Objects.requireNonNull(saveAccountPort, "saveAccountPort 不可為 null");
    }

    @Override
    public AccountId signIn(ExternalIdentity identity, String name, String fallbackName) {
        Objects.requireNonNull(identity, "identity 不可為 null");
        return loadAccountPort.load(identity)
                .map(Account::id)
                .orElseGet(() -> createFor(identity, name, fallbackName));
    }

    private AccountId createFor(ExternalIdentity identity, String name, String fallbackName) {
        Account account = Account.external(AccountId.newId(), name, fallbackName);
        if (saveAccountPort.saveIfIdentityFree(account, identity)) {
            return account.id();
        }
        // 剛剛查的時候還沒有、存的時候已經有了：同一個人在兩個分頁同時第一次登入，另一邊先存好了。
        // 用那邊建的帳號，兩個分頁登進的才是同一個人
        return loadAccountPort.load(identity)
                .map(Account::id)
                .orElseThrow(() -> new IllegalStateException("存帳號時說 " + identity + " 已經有帳號，卻查不到"));
    }
}
