package io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.Migrations;
import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity.Provider;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JdbcAccountAdapter 對真的 PostgreSQL（做法同 JdbcCalendarEventAdapterTest）。
 * 重點是 Google、GitHub 登入那半邊：撞 identity 時整筆撤掉、V10 的約束真的擋得住。
 */
@Testcontainers
@DisplayName("JdbcAccountAdapter（真的 PostgreSQL）")
class JdbcAccountAdapterTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static JdbcAccountAdapter adapter;
    static JdbcClient jdbc;

    private static final ExternalIdentity AMY_GOOGLE = new ExternalIdentity(Provider.GOOGLE, "google-sub-1");

    @BeforeAll
    static void migrateAndConnect() {
        Migrations.migrate(POSTGRES);
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
}
