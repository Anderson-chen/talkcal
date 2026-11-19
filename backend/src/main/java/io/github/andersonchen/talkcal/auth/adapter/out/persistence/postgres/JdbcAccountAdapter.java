package io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.ExternalIdentity;
import io.github.andersonchen.talkcal.auth.application.domain.model.MembershipTier;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.out.GuestStorePort;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** 表的定義在 db/migration/V9、V10、V14、V15。 */
public final class JdbcAccountAdapter implements SaveAccountPort, LoadAccountPort, GuestStorePort {

    private static final String COLUMNS = "account.id, account.display_name, account.username, account.password_hash, account.tier";

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
            // 撞名由資料庫的 UNIQUE 判斷，不先查再存：兩個人同時註冊同一個名字，只有一個會插入成功
            int inserted = jdbc.sql("""
                            INSERT INTO account (id, display_name, username, password_hash, tier)
                            VALUES (:id, :displayName, :username, :hash, :tier)
                            ON CONFLICT (username) DO NOTHING
                            """)
                    .param("id", account.id().value())
                    .param("displayName", account.displayName())
                    .param("username", login.username().value())
                    .param("hash", login.passwordHash())
                    .param("tier", account.tier().id())
                    .update();
            return inserted == 1;
        } catch (DataAccessException e) {
            throw new IllegalStateException("儲存帳號失敗：" + login.username(), e);
        }
    }

    @Override
    public boolean saveIfIdentityFree(Account account, ExternalIdentity identity) {
        try {
            // 同一個交易：identity 已經有人用了的話，剛插入的帳號也撤掉，不留下沒人登得進的帳號
            Boolean saved = transaction.execute(status -> {
                jdbc.sql("INSERT INTO account (id, display_name, tier) VALUES (:id, :displayName, :tier)")
                        .param("id", account.id().value())
                        .param("displayName", account.displayName())
                        .param("tier", account.tier().id())
                        .update();
                // 另一個交易正在插同一個 identity 時，PostgreSQL 會等那個交易結束再決定：
                // 那個交易 commit 了，這邊就是 0 列，兩邊不會都回報建立成功
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
    public UpgradeResult upgradeGuest(AccountId guest, Account.PasswordLogin login) {
        try {
            UpgradeResult result = transaction.execute(status -> {
                // tier = 'guest' 是條件：另一個分頁先註冊好了，這邊就是 0 列，不會把別人的帳號名稱、密碼蓋掉
                int updated = jdbc.sql("""
                                UPDATE account
                                SET username = :username, password_hash = :hash, display_name = :username,
                                    tier = 'free', last_active_at = NULL
                                WHERE id = :id AND tier = 'guest'
                                """)
                        .param("id", guest.value())
                        .param("username", login.username().value())
                        .param("hash", login.passwordHash())
                        .update();
                if (updated == 0) {
                    return UpgradeResult.NOT_A_GUEST;
                }
                // 不是訪客了，訪客 token 不能再拿來登入這個帳號
                jdbc.sql("DELETE FROM guest_token WHERE account_id = :id").param("id", guest.value()).update();
                return UpgradeResult.UPGRADED;
            });
            return Objects.requireNonNull(result);
        } catch (DuplicateKeyException e) {
            // 撞名由 username 的 UNIQUE 判斷；整個交易已經 rollback，帳號還是訪客
            return UpgradeResult.USERNAME_TAKEN;
        } catch (DataAccessException e) {
            throw new IllegalStateException("訪客升級成會員失敗：" + guest, e);
        }
    }

    @Override
    public void saveGuest(Account guest, String tokenHash, Instant now, Instant expiresAt) {
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("""
                                INSERT INTO account (id, display_name, tier, last_active_at)
                                VALUES (:id, :displayName, :tier, :now)
                                """)
                        .param("now", utc(now))
                        .param("id", guest.id().value())
                        .param("displayName", guest.displayName())
                        .param("tier", guest.tier().id())
                        .update();
                jdbc.sql("INSERT INTO guest_token (token_hash, account_id, expires_at) VALUES (:hash, :id, :expiresAt)")
                        .param("hash", tokenHash)
                        .param("id", guest.id().value())
                        .param("expiresAt", utc(expiresAt))
                        .update();
            });
        } catch (DataAccessException e) {
            throw new IllegalStateException("建立訪客帳號失敗：" + guest.id(), e);
        }
    }

    @Override
    public Optional<AccountId> touch(String tokenHash, Instant now, Instant newExpiresAt) {
        try {
            // 檢查（沒過期、還是訪客）和延期在同一句：兩個請求同時拿快過期的 token 回來，不會一個延期、一個看到已過期又被刪
            return jdbc.sql("""
                            WITH touched AS (
                                UPDATE guest_token
                                SET expires_at = :newExpiresAt
                                FROM account
                                WHERE guest_token.token_hash = :hash
                                  AND guest_token.expires_at > :now
                                  AND account.id = guest_token.account_id
                                  AND account.tier = 'guest'
                                RETURNING guest_token.account_id
                            )
                            UPDATE account SET last_active_at = :now
                            WHERE id IN (SELECT account_id FROM touched)
                            RETURNING id
                            """)
                    .param("hash", tokenHash)
                    .param("now", utc(now))
                    .param("newExpiresAt", utc(newExpiresAt))
                    .query((rs, n) -> new AccountId(rs.getObject("id", UUID.class)))
                    .optional();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取訪客 token 失敗", e);
        }
    }

    @Override
    public List<AccountId> guestsInactiveSince(Instant cutoff) {
        try {
            return jdbc.sql("SELECT id FROM account WHERE tier = 'guest' AND last_active_at < :cutoff")
                    .param("cutoff", utc(cutoff))
                    .query((rs, n) -> new AccountId(rs.getObject("id", UUID.class)))
                    .list();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取太久沒用的訪客帳號失敗", e);
        }
    }

    @Override
    public void delete(AccountId account) {
        try {
            // 只刪訪客：這個方法只給訪客的合併、清理用，條件寫在 SQL 裡，傳錯 id 也刪不到正式會員
            jdbc.sql("DELETE FROM account WHERE id = :id AND tier = 'guest'").param("id", account.value()).update();
        } catch (DataAccessException e) {
            throw new IllegalStateException("刪除訪客帳號失敗：" + account, e);
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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

    /**
     * 資料庫裡的值不合 domain 的規則（例如 CHECK 先放寬、程式還沒支援的等級）時丟 IllegalStateException：
     * 這是資料庫的資料問題，不是使用者輸入錯誤，所以不把 domain 的 IllegalArgumentException 直接往外丟。
     */
    private static Account toAccount(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        try {
            String username = rs.getString("username");
            // V10 的 CHECK 規定帳號名稱和密碼雜湊一起有、一起沒有
            Account.PasswordLogin login = username == null
                    ? null
                    : new Account.PasswordLogin(new Username(username), rs.getString("password_hash"));
            return new Account(new AccountId(rs.getObject("id", UUID.class)), rs.getString("display_name"), login,
                    MembershipTier.of(rs.getString("tier")));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("資料庫裡的帳號資料不合規則：" + id, e);
        }
    }
}
