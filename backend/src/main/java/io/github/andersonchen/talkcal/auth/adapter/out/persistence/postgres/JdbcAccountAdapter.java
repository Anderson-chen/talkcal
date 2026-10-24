package io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.out.DeleteAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 帳號存 PostgreSQL（表的定義在 db/migration/V9、V10）。寫法跟 calendar 的 JdbcCalendarEventAdapter 一致。
 */
public final class JdbcAccountAdapter implements SaveAccountPort, LoadAccountPort, DeleteAccountPort {

    private static final String COLUMNS = "account.id, account.display_name, account.username, account.password_hash";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcAccountAdapter(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    @Override
    public boolean saveIfUsernameFree(Account account) {
        Account.PasswordLogin login = account.password()
                .orElseThrow(() -> new IllegalArgumentException("帳密註冊的帳號要有帳號名稱和密碼"));
        try {
            // ON CONFLICT (username) DO NOTHING：撞名時插入 0 列、不丟錯。
            // 判斷撞名的是資料庫的 UNIQUE，不是「先查再存」—— 兩個人同時註冊同一個名字時，只有一個會成功
            int inserted = jdbc.sql("""
                            INSERT INTO account (id, display_name, username, password_hash)
                            VALUES (:id, :displayName, :username, :hash)
                            ON CONFLICT (username) DO NOTHING
                            """)
                    .param("id", account.id().value())
                    .param("displayName", account.displayName())
                    .param("username", login.username().value())
                    .param("hash", login.passwordHash())
                    .update();
            return inserted == 1;
        } catch (DataAccessException e) {
            throw new IllegalStateException("儲存帳號失敗：" + login.username(), e);
        }
    }

    @Override
    public boolean saveIfIdentityFree(Account account, ExternalIdentity identity) {
        try {
            // 兩個 INSERT 放在同一個交易：identity 已經有人用了的話，剛插入的帳號也要撤掉，不留下沒人登得進的帳號
            Boolean saved = transaction.execute(status -> {
                jdbc.sql("INSERT INTO account (id, display_name) VALUES (:id, :displayName)")
                        .param("id", account.id().value())
                        .param("displayName", account.displayName())
                        .update();
                // 撞到主鍵 (provider, subject) 時插入 0 列、不丟錯。另一個交易正在插同一個 identity 時，
                // PostgreSQL 會等它結束再決定：它 commit 了這邊就是 0 列，所以兩邊不會都以為自己建成功
                int linked = jdbc.sql("""
                                INSERT INTO account_identity (provider, subject, account_id)
                                VALUES (:provider, :subject, :accountId)
                                ON CONFLICT (provider, subject) DO NOTHING
                                """)
                        .param("provider", identity.provider().id())
                        .param("subject", identity.subject())
                        .param("accountId", account.id().value())
                        .update();
                if (linked == 0) {
                    status.setRollbackOnly();
                }
                return linked == 1;
            });
            return Boolean.TRUE.equals(saved);
        } catch (DataAccessException e) {
            throw new IllegalStateException("儲存帳號失敗：" + identity, e);
        }
    }

    @Override
    public Optional<Account> load(Username username) {
        return query("SELECT " + COLUMNS + " FROM account WHERE username = :key", username.value(), "帳號名稱 " + username);
    }

    @Override
    public Optional<Account> load(AccountId id) {
        return query("SELECT " + COLUMNS + " FROM account WHERE id = :key", id.value(), "帳號 " + id);
    }

    @Override
    public Optional<Account> load(ExternalIdentity identity) {
        try {
            return jdbc.sql("""
                            SELECT %s
                            FROM account_identity
                            JOIN account ON account.id = account_identity.account_id
                            WHERE account_identity.provider = :provider AND account_identity.subject = :subject
                            """.formatted(COLUMNS))
                    .param("provider", identity.provider().id())
                    .param("subject", identity.subject())
                    .query((rs, n) -> toAccount(rs))
                    .optional();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取帳號失敗：" + identity, e);
        }
    }

    @Override
    public boolean delete(AccountId id) {
        try {
            // account_identity 是 ON DELETE CASCADE（V10）：Google、GitHub 的對應跟著帳號一起刪，同一句 SQL、同一個交易
            return jdbc.sql("DELETE FROM account WHERE id = :id")
                    .param("id", id.value())
                    .update() > 0;
        } catch (DataAccessException e) {
            throw new IllegalStateException("刪除帳號失敗：" + id, e);
        }
    }

    private Optional<Account> query(String sql, Object key, String what) {
        try {
            return jdbc.sql(sql)
                    .param("key", key)
                    .query((rs, n) -> toAccount(rs))
                    .optional();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取帳號失敗：" + what, e);
        }
    }

    private static Account toAccount(ResultSet rs) throws SQLException {
        String username = rs.getString("username");
        // 資料庫的 CHECK 保證帳號名稱和密碼雜湊要嘛都有、要嘛都沒有
        Account.PasswordLogin login = username == null
                ? null
                : new Account.PasswordLogin(new Username(username), rs.getString("password_hash"));
        return new Account(new AccountId(rs.getObject("id", UUID.class)), rs.getString("display_name"), login);
    }
}
