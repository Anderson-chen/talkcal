package io.github.andersonchen.talkcal.auth.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.auth.application.domain.model.Account;
import io.github.andersonchen.talkcal.auth.application.domain.model.AccountId;
import io.github.andersonchen.talkcal.auth.application.domain.model.Username;
import io.github.andersonchen.talkcal.auth.application.port.out.LoadAccountPort;
import io.github.andersonchen.talkcal.auth.application.port.out.SaveAccountPort;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 帳號存 PostgreSQL（表的定義在 db/migration/V9__create_account.sql）。寫法跟 calendar 的 JdbcCalendarEventAdapter 一致。
 */
public final class JdbcAccountAdapter implements SaveAccountPort, LoadAccountPort {

    private final JdbcClient jdbc;

    public JdbcAccountAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
    }

    @Override
    public boolean saveIfUsernameFree(Account account) {
        try {
            // ON CONFLICT (username) DO NOTHING：撞名時插入 0 列、不丟錯。
            // 判斷撞名的是資料庫的 UNIQUE，不是「先查再存」—— 兩個人同時註冊同一個名字時，只有一個會成功
            int inserted = jdbc.sql("""
                            INSERT INTO account (id, username, password_hash)
                            VALUES (:id, :username, :hash)
                            ON CONFLICT (username) DO NOTHING
                            """)
                    .param("id", account.id().value())
                    .param("username", account.username().value())
                    .param("hash", account.passwordHash())
                    .update();
            return inserted == 1;
        } catch (DataAccessException e) {
            throw new IllegalStateException("儲存帳號失敗：" + account.username(), e);
        }
    }

    @Override
    public Optional<Account> load(Username username) {
        return query("username = :key", username.value(), "帳號名稱 " + username);
    }

    @Override
    public Optional<Account> load(AccountId id) {
        return query("id = :key", id.value(), "帳號 " + id);
    }

    private Optional<Account> query(String condition, Object key, String what) {
        try {
            return jdbc.sql("SELECT id, username, password_hash FROM account WHERE " + condition)
                    .param("key", key)
                    .query((rs, n) -> new Account(
                            new AccountId(rs.getObject("id", UUID.class)),
                            new Username(rs.getString("username")),
                            rs.getString("password_hash")))
                    .optional();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取帳號失敗：" + what, e);
        }
    }
}
