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
            jdbc.sql("ALTER TABLE account ADD CONSTRAINT account_tier_known CHECK (tier IN ('free', 'paid'))").update();
        }
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
