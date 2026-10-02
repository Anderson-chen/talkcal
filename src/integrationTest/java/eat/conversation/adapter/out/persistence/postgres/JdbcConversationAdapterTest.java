package eat.conversation.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.ConversationChangedException;
import eat.conversation.application.domain.model.ConversationId;

import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JdbcConversationAdapter 對真的 PostgreSQL。
 *
 * 用 Testcontainers 起一個乾淨的 PostgreSQL 容器（跟 deploy/ 同一個映像），測完就丟：
 * SQL 方言、ON CONFLICT、交易、鎖這些行為，只有真的 PostgreSQL 驗得準。
 * 不起 Spring：adapter 只需要 JdbcClient 和 TransactionTemplate，自己組比較快，
 * 也順便驗了 db/migration 的 SQL 在乾淨的資料庫上跑得起來（跟 app 啟動時 Flyway 做的是同一件事）。
 *
 * 需要 Docker。沒開 Docker 時 Testcontainers 會直接失敗 —— 這跟「llama-server 沒開就跳過」不同，
 * 因為容器是測試自己起的，Docker 是這個 source set 的前提，不是外部服務。
 */
@Testcontainers
@DisplayName("JdbcConversationAdapter（真的 PostgreSQL）")
class JdbcConversationAdapterTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static JdbcConversationAdapter adapter;
    static JdbcClient jdbc;

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        adapter = new JdbcConversationAdapter(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static Conversation.Message user(String text) {
        return new Conversation.Message(Conversation.Role.USER, text);
    }

    private static Conversation.Message assistant(String text) {
        return new Conversation.Message(Conversation.Role.ASSISTANT, text);
    }

    private static Conversation answered(Conversation conversation, String question, String reply) {
        conversation.ask(question);
        conversation.recordReply(reply);
        return conversation;
    }

    @Nested
    @DisplayName("存了讀得回來")
    class RoundTrip {

        @Test
        @DisplayName("新對話：系統指令、訊息、順序都原樣讀回來，版本變成 1")
        void savesNewConversation() {
            Conversation conversation = answered(Conversation.start("你是營養師"), "中午吃什麼", "牛肉麵");

            adapter.save(conversation);
            Conversation loaded = adapter.load(conversation.id()).orElseThrow();

            assertEquals(Optional.of("你是營養師"), loaded.instruction());
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), loaded.messages());
            assertEquals(1, loaded.version());
        }

        @Test
        @DisplayName("接續：讀出來、再問一輪、存回去，只多出新的兩則，版本變成 2")
        void appendsToExistingConversation() {
            Conversation first = answered(Conversation.start(), "中午吃什麼", "牛肉麵");
            adapter.save(first);

            Conversation continued = answered(adapter.load(first.id()).orElseThrow(), "熱量多少", "約 600 大卡");
            adapter.save(continued);
            Conversation loaded = adapter.load(first.id()).orElseThrow();

            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵"), user("熱量多少"), assistant("約 600 大卡")),
                    loaded.messages());
            assertEquals(2, loaded.version());
        }

        @Test
        @DisplayName("沒有訊息的對話也讀得回來（LEFT JOIN 那一列訊息欄位是 NULL）")
        void savesConversationWithoutMessages() {
            Conversation empty = Conversation.start("你是營養師");

            adapter.save(empty);

            assertEquals(List.of(), adapter.load(empty.id()).orElseThrow().messages());
        }

        @Test
        @DisplayName("找不到就是 Optional.empty()，不是例外")
        void missingConversationIsEmpty() {
            assertTrue(adapter.load(ConversationId.newId()).isEmpty());
        }
    }

    @Nested
    @DisplayName("樂觀鎖：後存的不能蓋掉先存的")
    class OptimisticLocking {

        @Test
        @DisplayName("兩個請求讀到同一版、各問一題：先存的成功，後存的被擋下，資料庫裡只有先存的那題")
        void secondWriterLoses() {
            Conversation original = answered(Conversation.start(), "中午吃什麼", "牛肉麵");
            adapter.save(original);
            Conversation tabA = adapter.load(original.id()).orElseThrow();
            Conversation tabB = adapter.load(original.id()).orElseThrow();

            adapter.save(answered(tabA, "熱量多少", "約 600 大卡"));

            assertThrows(ConversationChangedException.class,
                    () -> adapter.save(answered(tabB, "蛋白質多少", "約 30 克")));
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵"), user("熱量多少"), assistant("約 600 大卡")),
                    adapter.load(original.id()).orElseThrow().messages());
        }

        @Test
        @DisplayName("同一個新對話存兩次：第二次被擋下（版本 0 只能插入一次）")
        void newConversationCanOnlyBeInsertedOnce() {
            Conversation conversation = answered(Conversation.start(), "中午吃什麼", "牛肉麵");
            adapter.save(conversation);

            assertThrows(ConversationChangedException.class, () -> adapter.save(conversation));
        }
    }

    @Test
    @DisplayName("資料庫裡被手動改壞的紀錄，讀出來時被 domain 的規則擋下")
    void corruptedHistoryIsRejectedOnLoad() {
        ConversationId id = ConversationId.newId();
        jdbc.sql("INSERT INTO conversation (id, version) VALUES (:id, 1)").param("id", id.value()).update();
        // 違反規則 1：第一則就是回覆，前面沒有提問
        jdbc.sql("INSERT INTO conversation_message (conversation_id, position, role, text) VALUES (:id, 0, 'ASSISTANT', '牛肉麵')")
                .param("id", id.value()).update();

        assertThrows(IllegalStateException.class, () -> adapter.load(id));
    }
}
