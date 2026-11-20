package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.out.OwnedDataStorePort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 存了 owner_id 的表（V7、V11、V12、V13）一起改。新增一張存 owner_id 的表時，這裡要一起加，
 * 不然訪客的那份資料會留在已經刪掉的帳號名下（JdbcOwnedDataAdapterTest 會列出所有有 owner_id 欄位的表來對）。
 *
 * calendar_add_request 是冪等鍵，只留一天、只在那一次新增有用：搬過去也沒用，直接刪，
 * 也避免兩邊剛好有同一把鍵時違反主鍵的唯一性。
 */
public final class JdbcOwnedDataAdapter implements OwnedDataStorePort {

    private static final String[] OWNED_TABLES = {"calendar_event", "calendar_assistant_message", "assistant_proposal"};

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcOwnedDataAdapter(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    @Override
    public void transfer(OwnerId from, OwnerId to) {
        try {
            transaction.executeWithoutResult(status -> {
                for (String table : OWNED_TABLES) {
                    jdbc.sql("UPDATE " + table + " SET owner_id = :to WHERE owner_id = :from")
                            .param("from", from.value())
                            .param("to", to.value())
                            .update();
                }
                deleteAddRequests(from);
            });
        } catch (DataAccessException e) {
            throw new IllegalStateException("搬移 " + from + " 的資料到 " + to + " 失敗", e);
        }
    }

    @Override
    public void deleteAll(OwnerId owner) {
        try {
            transaction.executeWithoutResult(status -> {
                for (String table : OWNED_TABLES) {
                    jdbc.sql("DELETE FROM " + table + " WHERE owner_id = :owner").param("owner", owner.value()).update();
                }
                deleteAddRequests(owner);
            });
        } catch (DataAccessException e) {
            throw new IllegalStateException("刪除 " + owner + " 的資料失敗", e);
        }
    }

    private void deleteAddRequests(OwnerId owner) {
        jdbc.sql("DELETE FROM calendar_add_request WHERE owner_id = :owner").param("owner", owner.value()).update();
    }
}
