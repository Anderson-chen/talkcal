package io.github.andersonchen.talkcal.auth.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.MembershipTier;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.in.GuestAccessUseCase.GuestPass;
import io.github.andersonchen.talkcal.auth.application.port.in.UsernameTakenException;
import io.github.andersonchen.talkcal.auth.application.port.out.OwnedDataPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GuestAccountsService、RegisterAccountService.upgradeGuest")
class GuestAccountsServiceTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    /** 記下搬了誰、刪了誰 */
    static final class RecordedData implements OwnedDataPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public void transfer(AccountId from, AccountId to) {
            calls.add("transfer " + from + " -> " + to);
        }

        @Override
        public void deleteAll(AccountId owner) {
            calls.add("delete " + owner);
        }
    }

    private final InMemoryAccounts accounts = new InMemoryAccounts();
    private final RecordedData data = new RecordedData();

    private GuestAccountsService at(Instant now) {
        return new GuestAccountsService(accounts, accounts, data, WEEK, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("開訪客：帳號是訪客等級，token 7 天後到期，存的是雜湊不是 token 本身")
    void startStoresOnlyTheHash() {
        GuestPass pass = at(NOW).start();

        assertEquals(MembershipTier.GUEST, accounts.load(pass.accountId()).orElseThrow().tier());
        assertEquals(NOW.plus(WEEK), pass.expiresAt());
        assertTrue(accounts.tokens.containsKey(GuestAccountsService.hash(pass.token())));
        assertTrue(!accounts.tokens.containsKey(pass.token()));
    }

    @Test
    @DisplayName("token 回來：還是同一個訪客，期限從這次往後延 7 天")
    void resumeExtends() {
        GuestPass pass = at(NOW).start();
        Instant later = NOW.plus(Duration.ofDays(6));

        GuestPass resumed = at(later).resume(pass.token()).orElseThrow();
        assertEquals(pass.accountId(), resumed.accountId());
        assertEquals(later.plus(WEEK), resumed.expiresAt());
    }

    @Test
    @DisplayName("反例：過期的 token、不認得的 token、空白都回 empty")
    void resumeRejects() {
        GuestPass pass = at(NOW).start();
        assertEquals(Optional.empty(), at(NOW.plus(Duration.ofDays(8))).resume(pass.token()));
        assertEquals(Optional.empty(), at(NOW).resume("made-up"));
        assertEquals(Optional.empty(), at(NOW).resume(" "));
    }

    @Test
    @DisplayName("訪客登入別的帳號：資料搬過去、訪客帳號刪掉；反例：同一個帳號（註冊升級）什麼都不做")
    void mergeMovesAndDeletes() {
        GuestPass pass = at(NOW).start();
        AccountId amy = AccountId.newId();

        at(NOW).mergeInto(pass.accountId(), pass.accountId());
        assertTrue(data.calls.isEmpty());

        at(NOW).mergeInto(pass.accountId(), amy);
        assertEquals(List.of("transfer " + pass.accountId() + " -> " + amy), data.calls);
        assertEquals(Optional.empty(), accounts.load(pass.accountId()));
    }

    @Test
    @DisplayName("反例：已經不是訪客（另一個分頁先註冊好了），不搬資料")
    void mergeSkipsMembers() {
        Account member = Account.withPassword(AccountId.newId(), Username.of("amy"), "hash");
        accounts.saveIfUsernameFree(member);

        at(NOW).mergeInto(member.id(), AccountId.newId());
        assertTrue(data.calls.isEmpty());
        assertTrue(accounts.load(member.id()).isPresent());
    }

    @Test
    @DisplayName("清理：超過期限沒用的訪客連同資料刪掉；反例：最近用過的留著")
    void expireRemovesInactive() {
        GuestPass old = at(NOW).start();
        GuestPass recent = at(NOW).start();
        at(NOW.plus(Duration.ofDays(5))).resume(recent.token());

        int deleted = at(NOW.plus(Duration.ofDays(10))).expireInactiveSince(NOW.plus(Duration.ofDays(3)));

        assertEquals(1, deleted);
        assertEquals(List.of("delete " + old.accountId()), data.calls);
        assertTrue(accounts.load(recent.accountId()).isPresent());
    }

    @Test
    @DisplayName("訪客註冊：同一個帳號 ID、改成免費、token 不能再用；反例：撞名丟 UsernameTakenException，還是訪客")
    void upgradeKeepsTheId() {
        RegisterAccountService register = new RegisterAccountService(accounts, password -> "hashed:" + password);
        register.register("bob", "correct-horse");
        GuestPass pass = at(NOW).start();

        assertThrows(UsernameTakenException.class, () -> register.upgradeGuest(pass.accountId(), "bob", "correct-horse"));
        assertEquals(MembershipTier.GUEST, accounts.load(pass.accountId()).orElseThrow().tier());

        assertEquals(pass.accountId(), register.upgradeGuest(pass.accountId(), "amy", "correct-horse"));
        Account upgraded = accounts.load(pass.accountId()).orElseThrow();
        assertEquals(MembershipTier.FREE, upgraded.tier());
        assertEquals("amy", upgraded.password().orElseThrow().username().value());
        assertEquals(Optional.empty(), at(NOW).resume(pass.token()));
    }

    @Test
    @DisplayName("反例：不是訪客的帳號不能拿來升級")
    void upgradeRejectsMembers() {
        RegisterAccountService register = new RegisterAccountService(accounts, password -> "hashed:" + password);
        AccountId bob = register.register("bob", "correct-horse");
        assertThrows(IllegalArgumentException.class, () -> register.upgradeGuest(bob, "bobby", "correct-horse"));
        assertNotEquals("bobby", accounts.load(bob).orElseThrow().displayName());
    }
}
