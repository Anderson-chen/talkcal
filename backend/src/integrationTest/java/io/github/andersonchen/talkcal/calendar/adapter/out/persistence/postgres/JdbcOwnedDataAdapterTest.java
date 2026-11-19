package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.andersonchen.talkcal.TestPostgres;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 做法見 JdbcCalendarEventAdapterTest。資料用 SQL 直接寫進去，每張存了 owner_id 的表各一筆 */
@DisplayName("JdbcOwnedDataAdapter（真的 PostgreSQL）")
class JdbcOwnedDataAdapterTest {

    static final TestPostgres.Database DATABASE = TestPostgres.newDatabase().migrated();

    static JdbcClient jdbc;
    static JdbcOwnedDataAdapter adapter;

    private static final OwnerId GUEST = new OwnerId(UUID.fromString("3f1c8a2e-0000-4000-8000-000000000001"));
    private static final OwnerId AMY = new OwnerId(UUID.fromString("3f1c8a2e-0000-4000-8000-000000000002"));
    private static final OwnerId BOB = new OwnerId(UUID.fromString("3f1c8a2e-0000-4000-8000-000000000003"));

    // JdbcOwnedDataAdapter 處理的表：搬過去的三張，加上直接刪掉的冪等鍵
    private static final Set<String> HANDLED = Set.of("calendar_event", "calendar_assistant_message", "assistant_proposal",
            "calendar_add_request");

    @BeforeAll
    static void connect() {
        jdbc = JdbcClient.create(DATABASE.dataSource());
        adapter = new JdbcOwnedDataAdapter(jdbc, new TransactionTemplate(new DataSourceTransactionManager(DATABASE.dataSource())));
    }

    @BeforeEach
    void clean() {
        for (String table : HANDLED) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }

    private static void ownEverything(OwnerId owner) {
        UUID id = owner.value();
        jdbc.sql("""
                        INSERT INTO calendar_event (id, owner_id, title, start_at, end_at)
                        VALUES (:event, :owner, '牙醫', '2030-01-15 10:00', '2030-01-15 11:00')
                        """)
                .param("event", UUID.randomUUID()).param("owner", id).update();
        jdbc.sql("""
                        INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content)
                        VALUES (:owner, :conversation, 0, 'USER', '明天有什麼行程？')
                        """)
                .param("owner", id).param("conversation", UUID.randomUUID()).update();
        jdbc.sql("INSERT INTO assistant_proposal (id, owner_id) VALUES (:proposal, :owner)")
                .param("proposal", UUID.randomUUID()).param("owner", id).update();
        jdbc.sql("""
                        INSERT INTO calendar_add_request (owner_id, idempotency_key, request_hash, event_ids)
                        VALUES (:owner, 'k1', repeat('a', 64), ARRAY[]::uuid[])
                        """)
                .param("owner", id).update();
    }

    private static int owned(String table, OwnerId owner) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE owner_id = :owner")
                .param("owner", owner.value()).query(Integer.class).single();
    }

    @Test
    @DisplayName("反例：資料庫裡每張有 owner_id 的表都有處理到；新增一張存 owner_id 的表卻忘了加進來，這題會失敗")
    void everyOwnedTableIsHandled() {
        List<String> owned = jdbc.sql("""
                        SELECT table_name FROM information_schema.columns
                        WHERE column_name = 'owner_id' AND table_schema = 'public'
                        """)
                .query(String.class).list();
        assertEquals(new TreeSet<>(HANDLED), new TreeSet<>(owned));
    }

    @Test
    @DisplayName("搬移：訪客的行程、對話、卡片都到了 Amy 名下，冪等鍵刪掉；反例：Bob 的不動")
    void transferMovesEverything() {
        ownEverything(GUEST);
        ownEverything(BOB);

        adapter.transfer(GUEST, AMY);

        for (String table : List.of("calendar_event", "calendar_assistant_message", "assistant_proposal")) {
            assertEquals(1, owned(table, AMY), table);
            assertEquals(0, owned(table, GUEST), table);
        }
        assertEquals(0, owned("calendar_add_request", AMY));
        assertEquals(0, owned("calendar_add_request", GUEST));
        for (String table : HANDLED) {
            assertEquals(1, owned(table, BOB), table);
        }
    }

    @Test
    @DisplayName("刪除：訪客名下的全部刪掉；反例：Bob 的不動")
    void deleteAllRemovesEverything() {
        ownEverything(GUEST);
        ownEverything(BOB);

        adapter.deleteAll(GUEST);

        for (String table : HANDLED) {
            assertEquals(0, owned(table, GUEST), table);
            assertEquals(1, owned(table, BOB), table);
        }
    }
}
