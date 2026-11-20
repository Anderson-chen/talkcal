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

@DisplayName("GuestAccountsService、RegisterAccountService.registerFrom")
class GuestAccountsServiceTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration FINISH_MERGE_AFTER = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    /** 記下搬了誰、刪了誰；failFrom 不是 null 時，搬那個帳號的資料會失敗 */
    static final class RecordedData implements OwnedDataPort {
        final List<String> calls = new ArrayList<>();
        AccountId failFrom;

        @Override
        public void transfer(AccountId from, AccountId to) {
            if (from.equals(failFrom)) {
                throw new IllegalStateException("搬移失敗：" + from);
            }
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
        return new GuestAccountsService(accounts, accounts, data, WEEK, FINISH_MERGE_AFTER, Clock.fixed(now, ZoneOffset.UTC));
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
    @DisplayName("帶著 token 回來：還是同一個訪客，期限從這次往後延 7 天")
    void resumeExtends() {
        GuestPass pass = at(NOW).start();
        Instant later = NOW.plus(Duration.ofDays(6));

        GuestPass resumed = at(later).resume(pass.token()).orElseThrow();
        assertEquals(pass.accountId(), resumed.accountId());
        assertEquals(later.plus(WEEK), resumed.expiresAt());
    }

    @Test
    @DisplayName("反例：過期的 token、查不到的 token、空白都回 empty")
    void resumeRejects() {
        GuestPass pass = at(NOW).start();
        assertEquals(Optional.empty(), at(NOW.plus(Duration.ofDays(8))).resume(pass.token()));
        assertEquals(Optional.empty(), at(NOW).resume("made-up"));
        assertEquals(Optional.empty(), at(NOW).resume(" "));
    }

    @Test
    @DisplayName("session 還在的訪客記下使用：token 從這次往後延 7 天，清理不會刪掉；反例：已經不是訪客回 empty")
    void keepActiveExtendsWithoutToken() {
        GuestPass pass = at(NOW).start();
        Instant later = NOW.plus(Duration.ofDays(6));

        assertEquals(Optional.of(later.plus(WEEK)), at(later).keepActive(pass.accountId()));

        // 從開帳號算已經超過一週，從上一次使用算還沒有
        Instant past = NOW.plus(Duration.ofDays(10));
        assertEquals(0, at(past).expireInactive());
        assertEquals(pass.accountId(), at(past).resume(pass.token()).orElseThrow().accountId());

        Account member = Account.withPassword(AccountId.newId(), Username.of("amy"), "hash");
        accounts.saveIfUsernameFree(member);
        assertEquals(Optional.empty(), at(NOW).keepActive(member.id()));
    }

    @Test
    @DisplayName("反例：session 裡記的訪客已經被刪掉（清理、合併）回 empty")
    void keepActiveRejectsDeletedGuest() {
        GuestPass pass = at(NOW).start();
        accounts.delete(pass.accountId());

        assertEquals(Optional.empty(), at(NOW).keepActive(pass.accountId()));
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
    @DisplayName("反例：已經不是訪客（另一個分頁先註冊好了），不搬資料，之後也不再搬")
    void mergeSkipsMembers() {
        Account member = Account.withPassword(AccountId.newId(), Username.of("amy"), "hash");
        accounts.saveIfUsernameFree(member);

        at(NOW).mergeInto(member.id(), AccountId.newId());
        assertTrue(data.calls.isEmpty());
        assertTrue(accounts.load(member.id()).isPresent());
        assertEquals(0, at(NOW.plus(Duration.ofHours(1))).finishDue());
        assertTrue(data.calls.isEmpty());
    }

    @Test
    @DisplayName("合併過了 10 分鐘再搬一次，搬完就不再搬；反例：還沒過 10 分鐘不搬（還在處理中的請求可能還沒存）")
    void finishMovesAgainAfterAWhile() {
        GuestPass pass = at(NOW).start();
        AccountId amy = AccountId.newId();
        at(NOW).mergeInto(pass.accountId(), amy);
        data.calls.clear();

        assertEquals(0, at(NOW.plus(Duration.ofMinutes(9))).finishDue());
        assertTrue(data.calls.isEmpty());

        assertEquals(1, at(NOW.plus(Duration.ofMinutes(11))).finishDue());
        assertEquals(List.of("transfer " + pass.accountId() + " -> " + amy), data.calls);

        assertEquals(0, at(NOW.plus(Duration.ofMinutes(16))).finishDue());
        assertEquals(1, data.calls.size());
    }

    @Test
    @DisplayName("再搬一次失敗的那筆留著、下一次再搬，不擋後面的；全部試過一次才丟 IllegalStateException")
    void finishFailureKeepsTheMerge() {
        GuestPass first = at(NOW).start();
        GuestPass second = at(NOW).start();
        AccountId amy = AccountId.newId();
        at(NOW).mergeInto(first.accountId(), amy);
        at(NOW).mergeInto(second.accountId(), amy);
        data.calls.clear();
        data.failFrom = first.accountId();
        Instant later = NOW.plus(Duration.ofMinutes(11));

        IllegalStateException failed = assertThrows(IllegalStateException.class, () -> at(later).finishDue());
        assertEquals(1, failed.getSuppressed().length);
        assertEquals(List.of("transfer " + second.accountId() + " -> " + amy), data.calls);

        data.failFrom = null;
        assertEquals(1, at(later).finishDue());
        assertEquals("transfer " + first.accountId() + " -> " + amy, data.calls.getLast());
    }

    @Test
    @DisplayName("清理：超過期限沒用的訪客連同資料刪掉；反例：最近用過的留著")
    void expireRemovesInactive() {
        GuestPass old = at(NOW).start();
        GuestPass recent = at(NOW).start();
        at(NOW.plus(Duration.ofDays(5))).resume(recent.token());

        // 一週沒用就刪：第 10 天時，最後一次使用在第 3 天之前的才算
        int deleted = at(NOW.plus(Duration.ofDays(10))).expireInactive();

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

        assertThrows(UsernameTakenException.class, () -> register.registerFrom(pass.accountId(), "bob", "correct-horse"));
        assertEquals(MembershipTier.GUEST, accounts.load(pass.accountId()).orElseThrow().tier());

        assertEquals(pass.accountId(), register.registerFrom(pass.accountId(), "amy", "correct-horse"));
        Account upgraded = accounts.load(pass.accountId()).orElseThrow();
        assertEquals(MembershipTier.FREE, upgraded.tier());
        assertEquals("amy", upgraded.password().orElseThrow().username().value());
        assertEquals(Optional.empty(), at(NOW).resume(pass.token()));
    }

    @Test
    @DisplayName("反例：會員登入時註冊是另開一個新帳號，原本的帳號不變")
    void membersGetANewAccount() {
        RegisterAccountService register = new RegisterAccountService(accounts, password -> "hashed:" + password);
        AccountId bob = register.register("bob", "correct-horse");

        assertNotEquals(bob, register.registerFrom(bob, "bobby", "correct-horse"));
        assertEquals("bob", accounts.load(bob).orElseThrow().displayName());
    }
}
