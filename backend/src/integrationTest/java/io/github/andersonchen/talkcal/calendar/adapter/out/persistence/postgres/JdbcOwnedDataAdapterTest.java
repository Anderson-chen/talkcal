package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.TestPostgres;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

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
    static TransactionTemplate transaction;
    static JdbcOwnedDataAdapter adapter;

    private static final OwnerId GUEST = new OwnerId(UUID.fromString("3f1c8a2e-0000-4000-8000-000000000001"));
    private static final OwnerId AMY = new OwnerId(UUID.fromString("3f1c8a2e-0000-4000-8000-000000000002"));
    private static final OwnerId BOB = new OwnerId(UUID.fromString("3f1c8a2e-0000-4000-8000-000000000003"));

    // JdbcOwnedDataAdapter 處理的表：搬過去的三張，加上直接刪掉的冪等鍵
    private static final Set<String> HANDLED = Set.of("calendar_event", "calendar_assistant_message", "assistant_proposal",
            "calendar_add_request");

    @BeforeAll
    static void connect() {
        // 同一個 DataSource：dataSource() 每次回新的物件，分開給的話 JdbcClient 加入不了交易，每一句各自 commit，
        // 交易結束才釋放的 advisory lock 也是那一句結束就放掉
        DataSource dataSource = DATABASE.dataSource();
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        adapter = new JdbcOwnedDataAdapter(jdbc, transaction);
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

    private static void said(OwnerId owner, UUID conversation, int position, String type, String content) {
        jdbc.sql("""
                        INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content)
                        VALUES (:owner, :conversation, :position, :type, :content)
                        """)
                .param("owner", owner.value()).param("conversation", conversation).param("position", position)
                .param("type", type).param("content", content).update();
    }

    private static List<String> conversation(OwnerId owner, UUID conversation) {
        return jdbc.sql("""
                        SELECT content FROM calendar_assistant_message
                        WHERE owner_id = :owner AND conversation_id = :conversation ORDER BY position
                        """)
                .param("owner", owner.value()).param("conversation", conversation).query(String.class).list();
    }

    @Test
    @DisplayName("同一段對話兩邊都有（合併時還在處理中的那一輪，之後才存進訪客名下）：訪客的接在 Amy 那段後面；反例：只有訪客有的那段照原樣搬")
    void transferAppendsToTheSameConversation() {
        UUID shared = UUID.randomUUID();
        said(AMY, shared, 0, "USER", "明天有什麼行程？");
        said(AMY, shared, 1, "ASSISTANT", "明天沒有行程。");
        said(GUEST, shared, 0, "USER", "後天呢？");
        said(GUEST, shared, 1, "ASSISTANT", "後天也沒有。");
        UUID guestOnly = UUID.randomUUID();
        said(GUEST, guestOnly, 0, "USER", "下週呢？");

        adapter.transfer(GUEST, AMY);

        assertEquals(List.of("明天有什麼行程？", "明天沒有行程。", "後天呢？", "後天也沒有。"), conversation(AMY, shared));
        assertEquals(List.of("下週呢？"), conversation(AMY, guestOnly));
        assertEquals(0, owned("calendar_assistant_message", GUEST));
    }

    @Test
    @DisplayName("Amy 正在同一段對話接上新一輪、還沒 commit：搬移等那邊 commit 之後，訪客的接在那一輪後面，不違反主鍵的唯一性")
    void transferWaitsForTheConversationLock() throws Exception {
        UUID shared = UUID.randomUUID();
        said(AMY, shared, 0, "USER", "明天有什麼行程？");
        said(GUEST, shared, 0, "USER", "後天呢？");
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        try (ExecutorService background = Executors.newFixedThreadPool(2)) {
            // 模擬 JdbcAssistantMemoryRepository 接上新一輪：先取得那段對話的鎖，再插入下一個 position
            Future<?> amyReplies = background.submit(() -> transaction.executeWithoutResult(status -> {
                JdbcAssistantMemoryRepository.lock(jdbc, AMY.value(), shared);
                said(AMY, shared, 1, "ASSISTANT", "明天沒有行程。");
                inserted.countDown();
                try {
                    commit.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            // 失敗時也要放行 Amy 那邊，不然關掉 executor 時會一直等那個交易
            try {
                assertTrue(inserted.await(10, TimeUnit.SECONDS));
                Future<?> moving = background.submit(() -> adapter.transfer(GUEST, AMY));
                assertThrows(TimeoutException.class, () -> moving.get(500, TimeUnit.MILLISECONDS), "Amy 那邊還沒 commit，搬移在等");

                commit.countDown();
                amyReplies.get(10, TimeUnit.SECONDS);
                moving.get(10, TimeUnit.SECONDS);
            } finally {
                commit.countDown();
            }
        }

        assertEquals(List.of("明天有什麼行程？", "明天沒有行程。", "後天呢？"), conversation(AMY, shared));
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
