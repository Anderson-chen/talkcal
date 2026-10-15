package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 假的帳號儲存：跟資料庫一樣，帳號名稱、identity 撞了就回 false。
 * saved 依序記下存成功的帳號，測試用來檢查「有沒有存、存了什麼」。
 */
final class InMemoryAccounts implements SaveAccountPort, LoadAccountPort {

    final List<Account> saved = new ArrayList<>();
    final Map<ExternalIdentity, AccountId> identities = new HashMap<>();
    /** 查 identity 的次數：測同時登入時「第一次查不到、存的時候已經有了」 */
    int identityLookups;
    /** 模擬另一個請求在「查」和「存」之間搶先建好的帳號 */
    Account createdConcurrently;

    @Override
    public boolean saveIfUsernameFree(Account account) {
        Username username = account.password().orElseThrow().username();
        if (load(username).isPresent()) {
            return false;
        }
        return saved.add(account);
    }

    @Override
    public boolean saveIfIdentityFree(Account account, ExternalIdentity identity) {
        if (createdConcurrently != null) {
            saved.add(createdConcurrently);
            identities.put(identity, createdConcurrently.id());
        }
        if (identities.containsKey(identity)) {
            return false;
        }
        saved.add(account);
        identities.put(identity, account.id());
        return true;
    }

    @Override
    public Optional<Account> load(Username username) {
        return saved.stream()
                .filter(account -> account.password().map(login -> login.username().equals(username)).orElse(false))
                .findFirst();
    }

    @Override
    public Optional<Account> load(AccountId id) {
        return saved.stream().filter(account -> account.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Account> load(ExternalIdentity identity) {
        identityLookups++;
        return Optional.ofNullable(identities.get(identity)).flatMap(this::load);
    }
}
