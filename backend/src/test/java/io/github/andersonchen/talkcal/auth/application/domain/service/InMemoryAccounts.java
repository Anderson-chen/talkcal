package io.github.andersonchen.talkcal.auth.application.domain.service;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.MembershipTier;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.out.GuestStorePort;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 照 port 的約定，帳號名稱、identity 重複就回 false，不丟例外。
 * 訪客的 token 照 GuestStorePort 的約定：沒過期、帳號還是訪客才算數，用一次就延期。
 */
final class InMemoryAccounts implements SaveAccountPort, LoadAccountPort, GuestStorePort {

    record Token(AccountId account, Instant expiresAt) {
    }

    final Map<String, Token> tokens = new HashMap<>();
    final Map<AccountId, Instant> lastActive = new HashMap<>();

    final List<Account> saved = new ArrayList<>();
    final Map<ExternalIdentity, AccountId> identities = new HashMap<>();
    /** 測同時登入用：第一次查不到、存的時候已經有了 */
    int identityLookups;
    /** 另一個請求在查和存之間搶先建好的帳號 */
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
    public UpgradeResult upgradeGuest(AccountId guest, Account.PasswordLogin login) {
        Optional<Account> current = load(guest).filter(Account::isGuest);
        if (current.isEmpty()) {
            return UpgradeResult.NOT_A_GUEST;
        }
        if (load(login.username()).isPresent()) {
            return UpgradeResult.USERNAME_TAKEN;
        }
        saved.remove(current.get());
        saved.add(new Account(guest, login.username().value(), login, MembershipTier.FREE));
        tokens.values().removeIf(token -> token.account().equals(guest));
        lastActive.remove(guest);
        return UpgradeResult.UPGRADED;
    }

    @Override
    public boolean upgradeGuestWithIdentity(Account upgraded, ExternalIdentity identity) {
        Optional<Account> current = load(upgraded.id()).filter(Account::isGuest);
        if (current.isEmpty() || identities.containsKey(identity)) {
            return false;
        }
        saved.remove(current.get());
        saved.add(upgraded);
        identities.put(identity, upgraded.id());
        tokens.values().removeIf(token -> token.account().equals(upgraded.id()));
        lastActive.remove(upgraded.id());
        return true;
    }

    @Override
    public void saveGuest(Account guest, String tokenHash, Instant now, Instant expiresAt) {
        saved.add(guest);
        tokens.put(tokenHash, new Token(guest.id(), expiresAt));
        lastActive.put(guest.id(), now);
    }

    @Override
    public Optional<AccountId> touch(String tokenHash, Instant now, Instant newExpiresAt) {
        Token token = tokens.get(tokenHash);
        if (token == null || !token.expiresAt().isAfter(now) || load(token.account()).filter(Account::isGuest).isEmpty()) {
            return Optional.empty();
        }
        tokens.put(tokenHash, new Token(token.account(), newExpiresAt));
        lastActive.put(token.account(), now);
        return Optional.of(token.account());
    }

    @Override
    public boolean keepActive(AccountId guest, Instant now, Instant newExpiresAt) {
        if (load(guest).filter(Account::isGuest).isEmpty()) {
            return false;
        }
        tokens.replaceAll((hash, token) -> token.account().equals(guest) && token.expiresAt().isAfter(now)
                ? new Token(guest, newExpiresAt)
                : token);
        lastActive.put(guest, now);
        return true;
    }

    @Override
    public List<AccountId> guestsInactiveSince(Instant cutoff) {
        return lastActive.entrySet().stream()
                .filter(entry -> entry.getValue().isBefore(cutoff))
                .map(Map.Entry::getKey)
                .filter(id -> load(id).filter(Account::isGuest).isPresent())
                .toList();
    }

    @Override
    public void delete(AccountId account) {
        saved.removeIf(saved -> saved.id().equals(account) && saved.isGuest());
        tokens.values().removeIf(token -> token.account().equals(account));
        lastActive.remove(account);
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
