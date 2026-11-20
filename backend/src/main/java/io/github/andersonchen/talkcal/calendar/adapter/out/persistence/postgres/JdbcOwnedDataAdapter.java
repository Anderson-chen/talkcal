package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.out.OwnedDataStorePort;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * 存了 owner_id 的表（V7、V11、V12、V13）一起改。新增一張存 owner_id 的表時，搬移和刪除都要一起加，
 * 不然訪客的那份資料會留在已經刪掉的帳號名下（JdbcOwnedDataAdapterTest 會列出所有有 owner_id 欄位的表來對）。
 *
 * calendar_add_request 是冪等鍵，只留一天、只在那一次新增有用：搬過去也沒用，直接刪，
 * 也避免兩邊剛好有同一把鍵時違反主鍵的唯一性。
 */
public final class JdbcOwnedDataAdapter implements OwnedDataStorePort {

    private static final String[] OWNED_TABLES = {"calendar_event", "calendar_assistant_message", "assistant_proposal"};
    // 搬的時候直接改 owner_id 的表；calendar_assistant_message 要另外編 position，見 transferMessages
    private static final String[] TRANSFERRED_AS_IS = {"calendar_event", "assistant_proposal"};

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
                for (String table : TRANSFERRED_AS_IS) {
                    jdbc.sql("UPDATE " + table + " SET owner_id = :to WHERE owner_id = :from")
                            .param("from", from.value())
                            .param("to", to.value())
                            .update();
                }
                transferMessages(from, to);
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

    /**
     * to 名下可能已經有同一段對話：訪客合併時先搬過去的前幾輪在 to 名下，合併時還在處理中的那一輪之後才存進 from 名下，
     * 再搬一次時兩邊都有。兩邊的 position 都從 0 起算，直接改 owner_id 會違反主鍵的唯一性，
     * 所以 from 的那幾則照原本的順序接在 to 那段的最後面。position 只用來排順序，不必連號。
     */
    private void transferMessages(OwnerId from, OwnerId to) {
        jdbc.sql("""
                        UPDATE calendar_assistant_message moved
                        SET owner_id = :to,
                            position = moved.position + coalesce((
                                SELECT max(existing.position) + 1 FROM calendar_assistant_message existing
                                WHERE existing.owner_id = :to AND existing.conversation_id = moved.conversation_id), 0)
                        WHERE moved.owner_id = :from
                        """)
                .param("from", from.value())
                .param("to", to.value())
                .update();
    }

    private void deleteAddRequests(OwnerId owner) {
        jdbc.sql("DELETE FROM calendar_add_request WHERE owner_id = :owner").param("owner", owner.value()).update();
    }
}
