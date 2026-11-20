package io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.TestPostgres;
import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity.Provider;
import io.github.andersonchen.talkcal.auth.application.domain.model.MembershipTier;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.out.GuestStorePort.Merge;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 做法見 JdbcCalendarEventAdapterTest。
 */
@DisplayName("JdbcAccountAdapter（真的 PostgreSQL）")
class JdbcAccountAdapterTest {

    static final TestPostgres.Database DATABASE = TestPostgres.newDatabase().migrated();

    static JdbcAccountAdapter adapter;
    static JdbcClient jdbc;

    private static final ExternalIdentity AMY_GOOGLE = new ExternalIdentity(Provider.GOOGLE, "google-sub-1");

    @BeforeAll
    static void migrateAndConnect() {
        DriverManagerDataSource dataSource = DATABASE.dataSource();
        jdbc = JdbcClient.create(dataSource);
        adapter = new JdbcAccountAdapter(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM account").update();
    }

    private static int accounts() {
        return jdbc.sql("SELECT count(*) FROM account").query(Integer.class).single();
    }

    @Test
    @DisplayName("第一次用 Google 登入：存帳號和 identity，用 identity 查得回來，沒有帳號名稱和密碼")
    void savesAndLoadsByIdentity() {
        Account amy = Account.external(AccountId.newId(), "Amy Chen", null);

        assertTrue(adapter.saveIfIdentityFree(amy, AMY_GOOGLE));

        assertEquals(amy, adapter.load(AMY_GOOGLE).orElseThrow());
        assertEquals(amy, adapter.load(amy.id()).orElseThrow());
        assertTrue(adapter.load(new ExternalIdentity(Provider.GITHUB, "google-sub-1")).isEmpty(), "反例：別的 provider 同一個 ID 查不到");
    }

    @Test
    @DisplayName("identity 已經對到別的帳號：回 false，剛插入的帳號跟著撤掉（不留下沒人登得進的帳號）")
    void identityTakenRollsBackAccount() {
        Account first = Account.external(AccountId.newId(), "Amy", null);
        adapter.saveIfIdentityFree(first, AMY_GOOGLE);

        boolean saved = adapter.saveIfIdentityFree(Account.external(AccountId.newId(), "Amy（另一個分頁）", null), AMY_GOOGLE);

        assertFalse(saved);
        assertEquals(1, accounts());
        assertEquals(first.id(), adapter.load(AMY_GOOGLE).orElseThrow().id());
    }

    @Test
    @DisplayName("帳密註冊的帳號照舊：顯示名稱是帳號名稱，用帳號名稱查得到（V10 補 display_name 不影響）")
    void passwordAccountsStillWork() {
        Account amy = Account.withPassword(AccountId.newId(), new Username("amy"), "{bcrypt}hash");

        assertTrue(adapter.saveIfUsernameFree(amy));

        assertEquals(amy, adapter.load(new Username("amy")).orElseThrow());
    }

    @Test
    @DisplayName("V10 的約束：只有帳號名稱沒有密碼（或反過來）塞不進去；反例：兩個都沒有可以，而且好幾列都沒有也不撞 UNIQUE")
    void passwordLoginMustBeComplete() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                "INSERT INTO account (id, display_name, username) VALUES (:id, 'amy', 'amy')")
                .param("id", UUID.randomUUID()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                "INSERT INTO account (id, display_name, password_hash) VALUES (:id, 'amy', 'x')")
                .param("id", UUID.randomUUID()).update());

        adapter.saveIfIdentityFree(Account.external(AccountId.newId(), "Amy", null), AMY_GOOGLE);
        adapter.saveIfIdentityFree(Account.external(AccountId.newId(), "Bob", null), new ExternalIdentity(Provider.GITHUB, "42"));
        assertEquals(2, accounts());
    }

    @Test
    @DisplayName("帳號刪掉時，它的 identity 跟著刪：同一個 Google 帳號下次登入會建新帳號，不會指到不存在的帳號")
    void identityIsDeletedWithAccount() {
        Account amy = Account.external(AccountId.newId(), "Amy", null);
        adapter.saveIfIdentityFree(amy, AMY_GOOGLE);

        jdbc.sql("DELETE FROM account WHERE id = :id").param("id", amy.id().value()).update();

        assertTrue(adapter.load(AMY_GOOGLE).isEmpty());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM account_identity").query(Integer.class).single());
    }

    @Test
    @DisplayName("新帳號存成免費；照 README 的 SQL 改成付費後，讀回來是付費")
    void tierIsStoredAndLoaded() {
        Account amy = Account.withPassword(AccountId.newId(), new Username("amy"), "{bcrypt}hash");
        adapter.saveIfUsernameFree(amy);
        Account bob = Account.external(AccountId.newId(), "Bob", null);
        adapter.saveIfIdentityFree(bob, AMY_GOOGLE);

        assertEquals(MembershipTier.FREE, adapter.load(amy.id()).orElseThrow().tier());
        assertEquals(MembershipTier.FREE, adapter.load(AMY_GOOGLE).orElseThrow().tier());

        jdbc.sql("UPDATE account SET tier = 'paid' WHERE username = 'amy'").update();

        assertEquals(MembershipTier.PAID, adapter.load(new Username("amy")).orElseThrow().tier());
        assertEquals(MembershipTier.FREE, adapter.load(bob.id()).orElseThrow().tier(), "反例：沒改到的帳號還是免費");
    }

    @Test
    @DisplayName("V14 的約束：寫入不存在的等級（gold、大寫的 PAID、NULL）資料庫拒絕，原本的等級不變")
    void unknownTierIsRejected() {
        Account amy = Account.withPassword(AccountId.newId(), new Username("amy"), "{bcrypt}hash");
        adapter.saveIfUsernameFree(amy);

        for (String tier : new String[] {"gold", "PAID", ""}) {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("UPDATE account SET tier = :tier")
                    .param("tier", tier).update(), tier);
        }
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("UPDATE account SET tier = NULL").update());

        assertEquals(MembershipTier.FREE, adapter.load(amy.id()).orElseThrow().tier());
    }

    @Test
    @DisplayName("資料庫裡有程式不支援的等級（CHECK 先放寬了）：讀取時丟 IllegalStateException，當成讀取失敗，不是使用者輸入錯誤")
    void unsupportedTierIsReadFailure() {
        Account amy = Account.withPassword(AccountId.newId(), new Username("amy"), "{bcrypt}hash");
        adapter.saveIfUsernameFree(amy);
        jdbc.sql("ALTER TABLE account DROP CONSTRAINT account_tier_known").update();
        try {
            jdbc.sql("UPDATE account SET tier = 'pro'").update();

            assertThrows(IllegalStateException.class, () -> adapter.load(amy.id()));
        } finally {
            jdbc.sql("DELETE FROM account").update();
            // 加回 V15 的 CHECK（多了 guest）：這個 class 的其他測試會存訪客帳號
            jdbc.sql("ALTER TABLE account ADD CONSTRAINT account_tier_known CHECK (tier IN ('guest', 'free', 'paid'))").update();
        }
    }

    // ── 訪客用 Google、GitHub 註冊 ─────────────────────────────────────

    private static Account guestWithToken() {
        Account guest = Account.guest(AccountId.newId());
        Instant now = Instant.now();
        adapter.saveGuest(guest, "a".repeat(64), now, now.plus(Duration.ofDays(7)));
        return guest;
    }

    private static int guestTokens() {
        return jdbc.sql("SELECT count(*) FROM guest_token").query(Integer.class).single();
    }

    @Test
    @DisplayName("訪客用 Google 註冊：同一個帳號改成免費、名字換成 Google 給的，identity 對到這個帳號，訪客 token 刪掉")
    void guestUpgradesWithIdentity() {
        Account guest = guestWithToken();

        assertTrue(adapter.upgradeGuestWithIdentity(Account.external(guest.id(), "Amy Chen", null), AMY_GOOGLE));

        Account upgraded = adapter.load(AMY_GOOGLE).orElseThrow();
        assertEquals(guest.id(), upgraded.id());
        assertEquals(MembershipTier.FREE, upgraded.tier());
        assertEquals("Amy Chen", upgraded.displayName());
        assertEquals(0, guestTokens());
        assertEquals(1, accounts());
    }

    @Test
    @DisplayName("反例：帳號已經不是訪客（另一個分頁先註冊好了）：回 false，名字、等級不變，identity 沒有對到這個帳號")
    void memberIsNotUpgradedWithIdentity() {
        Account amy = Account.withPassword(AccountId.newId(), new Username("amy"), "{bcrypt}hash");
        adapter.saveIfUsernameFree(amy);

        assertFalse(adapter.upgradeGuestWithIdentity(Account.external(amy.id(), "Amy Chen", null), AMY_GOOGLE));

        assertEquals(amy, adapter.load(amy.id()).orElseThrow());
        assertTrue(adapter.load(AMY_GOOGLE).isEmpty());
    }

    @Test
    @DisplayName("反例：identity 已經對到別的帳號：回 false，訪客的升級一起撤掉，還是訪客、token 還在")
    void identityTakenRollsBackUpgrade() {
        Account other = Account.external(AccountId.newId(), "Amy（另一個分頁）", null);
        adapter.saveIfIdentityFree(other, AMY_GOOGLE);
        Account guest = guestWithToken();

        assertFalse(adapter.upgradeGuestWithIdentity(Account.external(guest.id(), "Amy Chen", null), AMY_GOOGLE));

        assertEquals(guest, adapter.load(guest.id()).orElseThrow());
        assertEquals(other.id(), adapter.load(AMY_GOOGLE).orElseThrow().id());
        assertEquals(1, guestTokens());
    }

    // ── session 還在的訪客記下使用 ───────────────────────────────────────

    private static Instant expiresAt(String tokenHash) {
        return jdbc.sql("SELECT expires_at FROM guest_token WHERE token_hash = :hash").param("hash", tokenHash)
                .query(OffsetDateTime.class).single().toInstant();
    }

    @Test
    @DisplayName("訪客記下使用：最後使用時間改成這次，還沒過期的 token 延期；反例：已經過期的 token 不延")
    void keepActiveExtendsGuest() {
        Account guest = Account.guest(AccountId.newId());
        Instant started = Instant.parse("2026-11-01T00:00:00Z");
        String valid = "a".repeat(64);
        String expired = "b".repeat(64);
        adapter.saveGuest(guest, valid, started, started.plus(Duration.ofDays(7)));
        jdbc.sql("INSERT INTO guest_token (token_hash, account_id, expires_at) VALUES (:hash, :id, :at)")
                .param("hash", expired).param("id", guest.id().value())
                .param("at", OffsetDateTime.ofInstant(started.minusSeconds(1), ZoneOffset.UTC)).update();
        Instant now = started.plus(Duration.ofDays(6));

        assertTrue(adapter.keepActive(guest.id(), now, now.plus(Duration.ofDays(7))));

        assertEquals(now.plus(Duration.ofDays(7)), expiresAt(valid));
        assertEquals(started.minusSeconds(1), expiresAt(expired));
        assertTrue(adapter.guestsInactiveSince(now).isEmpty());
        assertEquals(List.of(guest.id()), adapter.guestsInactiveSince(now.plusSeconds(1)));
    }

    @Test
    @DisplayName("反例：會員、不存在的帳號回 false，什麼都不改")
    void keepActiveIgnoresMembers() {
        Account amy = Account.withPassword(AccountId.newId(), new Username("amy"), "{bcrypt}hash");
        adapter.saveIfUsernameFree(amy);
        Instant now = Instant.now();

        assertFalse(adapter.keepActive(amy.id(), now, now.plus(Duration.ofDays(7))));
        assertFalse(adapter.keepActive(AccountId.newId(), now, now.plus(Duration.ofDays(7))));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM account WHERE last_active_at IS NOT NULL").query(Integer.class).single());
    }

    // ── 訪客併進正式帳號 ───────────────────────────────────────────────

    private static Account member(String username) {
        Account account = Account.withPassword(AccountId.newId(), new Username(username), "{bcrypt}hash");
        adapter.saveIfUsernameFree(account);
        return account;
    }

    @Test
    @DisplayName("訪客併進 Amy：訪客帳號和 token 刪掉，合併時間之後查得到這次合併；forgetMerge 之後查不到")
    void deleteMergedRecordsTheMerge() {
        Account amy = member("amy");
        Account guest = guestWithToken();
        Instant mergedAt = Instant.parse("2026-11-20T00:00:00Z");

        adapter.deleteMerged(guest.id(), amy.id(), mergedAt);

        assertTrue(adapter.load(guest.id()).isEmpty());
        assertEquals(0, guestTokens());
        assertTrue(adapter.mergedBefore(mergedAt).isEmpty());
        assertEquals(List.of(new Merge(guest.id(), amy.id())), adapter.mergedBefore(mergedAt.plusSeconds(1)));

        adapter.forgetMerge(guest.id());
        assertTrue(adapter.mergedBefore(mergedAt.plusSeconds(1)).isEmpty());
    }

    @Test
    @DisplayName("反例：帳號已經不是訪客（另一個分頁剛註冊）：不刪，也不記合併")
    void deleteMergedSkipsMembers() {
        Account amy = member("amy");
        Account bob = member("bob");
        Instant now = Instant.now();

        adapter.deleteMerged(bob.id(), amy.id(), now);

        assertEquals(bob, adapter.load(bob.id()).orElseThrow());
        assertTrue(adapter.mergedBefore(now.plusSeconds(1)).isEmpty());
    }

    @Test
    @DisplayName("併進的帳號刪掉時，合併紀錄跟著刪：已經沒有帳號可以搬過去")
    void mergeGoesWithTheTarget() {
        Account amy = member("amy");
        Instant now = Instant.now();
        adapter.deleteMerged(guestWithToken().id(), amy.id(), now);

        jdbc.sql("DELETE FROM account WHERE id = :id").param("id", amy.id().value()).update();

        assertTrue(adapter.mergedBefore(now.plusSeconds(1)).isEmpty());
    }

    @Test
    @DisplayName("反例：V14 之前就有的帳號（有帳號名稱、沒有帳號名稱的各一個），migration 之後都是免費會員")
    void existingAccountsBecomeFree() {
        TestPostgres.Database old = TestPostgres.newDatabase().migratedTo("13");
        JdbcClient oldJdbc = JdbcClient.create(old.dataSource());
        oldJdbc.sql("INSERT INTO account (id, display_name, username, password_hash) VALUES (:id, 'amy', 'amy', 'x')")
                .param("id", UUID.randomUUID()).update();
        oldJdbc.sql("INSERT INTO account (id, display_name) VALUES (:id, 'Bob')")
                .param("id", UUID.randomUUID()).update();

        old.migrated();

        assertEquals(List.of("free", "free"), oldJdbc.sql("SELECT tier FROM account").query(String.class).list());
    }
}
