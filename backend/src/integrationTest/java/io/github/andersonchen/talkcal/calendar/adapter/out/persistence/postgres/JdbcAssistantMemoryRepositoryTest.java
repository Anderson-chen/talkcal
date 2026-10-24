package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 對話記憶存進真的 PostgreSQL、讀回來，工具呼叫和工具結果一個欄位都不能少 —— 這張表存在的理由就是這個。
 */
@Testcontainers
@DisplayName("JdbcAssistantMemoryRepository（真的 PostgreSQL）")
class JdbcAssistantMemoryRepositoryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final OwnerId OTHER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000002");

    // 測試用小一點，截舊輪的測試才不必造很多輪
    private static final int KEEP_TURNS = 3;

    static JdbcAssistantMemoryRepository store;
    static DriverManagerDataSource dataSource;
    static JdbcClient jdbc;

    private final String id = UUID.randomUUID().toString();
    // 大部分的測試只看一個人：OWNER 名下的對話
    private final ChatMemory memory = store.of(OWNER);

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcAssistantMemoryRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), KEEP_TURNS);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
    }

    private static List<Message> oneTurnWithTool() {
        return List.of(
                new UserMessage("晚上"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "propose_events",
                                "{\"description\":\"明天晚上七點吃飯\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "propose_events", "已顯示 1 張卡片")))
                        .build(),
                new AssistantMessage("請確認卡片。"));
    }

    @Test
    @DisplayName("存了讀得回來：順序、類型、文字、工具呼叫（id、名稱、參數）、工具結果都一樣")
    void roundTripsToolMessages() {
        memory.add(id, oneTurnWithTool());

        List<Message> read = memory.get(id);

        assertEquals(4, read.size());
        assertEquals("晚上", read.get(0).getText());
        AssistantMessage call = assertInstanceOf(AssistantMessage.class, read.get(1));
        assertEquals(List.of(new AssistantMessage.ToolCall("call-1", "function", "propose_events", "{\"description\":\"明天晚上七點吃飯\"}")),
                call.getToolCalls());
        ToolResponseMessage result = assertInstanceOf(ToolResponseMessage.class, read.get(2));
        assertEquals(List.of(new ToolResponseMessage.ToolResponse("call-1", "propose_events", "已顯示 1 張卡片")), result.getResponses());
        assertEquals("請確認卡片。", read.get(3).getText());
        assertTrue(((AssistantMessage) read.get(3)).getToolCalls().isEmpty());
    }

    @Test
    @DisplayName("#50 add 是往後接，不是整段取代：存第二次，第一次的還在，第二次的接在後面")
    void addAppends() {
        memory.add(id, oneTurnWithTool());
        memory.add(id, List.of(new UserMessage("嗨"), new AssistantMessage("嗨！")));

        assertEquals(List.of("晚上", "", "", "請確認卡片。", "嗨", "嗨！"), memory.get(id).stream().map(Message::getText).toList());
    }

    @Test
    @DisplayName("#50 遺失更新：兩個請求讀到同一份歷史、各自存這一輪，兩輪都留著（舊的整段寫回只會剩晚存的那輪）")
    void twoRequestsReadingTheSameHistoryBothSurvive() {
        memory.add(id, List.of(new UserMessage("開頭"), new AssistantMessage("好")));
        // 兩個請求都先讀，讀到的是同一份
        assertEquals(memory.get(id), memory.get(id));

        // 各自跑完模型，只存自己這一輪；後送的先跑完
        memory.add(id, List.of(new UserMessage("第二句"), new AssistantMessage("收到第二句")));
        memory.add(id, List.of(new UserMessage("第一句"), new AssistantMessage("收到第一句")));

        assertEquals(List.of("開頭", "好", "第二句", "收到第二句", "第一句", "收到第一句"),
                memory.get(id).stream().map(Message::getText).toList());
    }

    @Test
    @DisplayName("#50 真的同時寫：多條執行緒同一瞬間往同一段對話接，一輪都不少、position 不重複、每一輪不被拆開")
    void concurrentAddsAreSerialized() throws Exception {
        // 反例：拿掉 advisory lock，大家讀到同一個 max(position)，撞主鍵（duplicate key），這個測試是紅的
        int writers = 8;
        // 每次 add 都從 DriverManagerDataSource 開一條新的連線，各自的交易在資料庫那邊真的同時跑
        JdbcAssistantMemoryRepository roomy = new JdbcAssistantMemoryRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), 100);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String said = "第 " + i + " 句";
                done.add(pool.submit(() -> {
                    start.await();
                    roomy.of(OWNER).add(id, List.of(new UserMessage(said), new AssistantMessage("收到" + said)));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Message> stored = memory.get(id);
        assertEquals(writers * 2, stored.size());
        for (int i = 0; i < stored.size(); i += 2) {
            // 一輪的兩則緊緊相鄰：別人的那輪不會插進中間
            assertEquals("收到" + stored.get(i).getText(), stored.get(i + 1).getText());
        }
        List<Integer> positions = jdbc.sql("SELECT position FROM calendar_assistant_message WHERE conversation_id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).list();
        assertEquals(writers * 2, new HashSet<>(positions).size());
    }

    @Test
    @DisplayName("只留最近 keepTurns 輪：接上新的一輪就刪掉更早的，從使用者的話那裡切，不拆散工具呼叫和結果")
    void dropsOldTurnsAtTurnBoundaries() {
        for (int turn = 0; turn < KEEP_TURNS + 2; turn++) {
            memory.add(id, oneTurnWithTool());
            memory.add(id, List.of(new UserMessage("第 " + turn + " 輪"), new AssistantMessage("好")));
        }

        List<Message> kept = memory.get(id);

        // 最後 3 輪：第 3 輪、工具那輪、第 4 輪
        assertEquals(List.of("第 3 輪", "好", "晚上", "", "", "請確認卡片。", "第 4 輪", "好"),
                kept.stream().map(Message::getText).toList());
        assertInstanceOf(ToolResponseMessage.class, kept.get(4));
    }

    @Test
    @DisplayName("截舊輪只動這個人、這段對話：同一個人的另一段、別人同一個 conversationId 的，都不受影響")
    void droppingOnlyTouchesThisConversation() {
        String other = UUID.randomUUID().toString();
        memory.add(other, List.of(new UserMessage("另一段")));
        store.of(OTHER).add(id, List.of(new UserMessage("別人")));
        for (int turn = 0; turn < KEEP_TURNS + 2; turn++) {
            memory.add(id, List.of(new UserMessage("我 " + turn)));
        }

        assertEquals(KEEP_TURNS, memory.get(id).size());
        assertEquals(1, memory.get(other).size());
        assertEquals(1, store.of(OTHER).get(id).size());
    }

    @Test
    @DisplayName("keepTurns 小於 1 不收：0 輪等於什麼都不記")
    void keepTurnsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcAssistantMemoryRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), 0));
    }

    @Test
    @DisplayName("system 不存（每一輪由程式重新給）")
    void systemIsNotStored() {
        memory.add(id, List.of(new SystemMessage("你是助理"), new UserMessage("嗨"), new AssistantMessage("嗨！")));

        assertEquals(2, memory.get(id).size());
    }

    @Test
    @DisplayName("對話之間互不干擾；刪掉一段只刪那段")
    void conversationsAreSeparate() {
        String other = UUID.randomUUID().toString();
        memory.add(id, oneTurnWithTool());
        memory.add(other, List.of(new UserMessage("另一段")));

        memory.clear(id);

        assertTrue(memory.get(id).isEmpty());
        assertEquals(1, memory.get(other).size());
    }

    @Test
    @DisplayName("清舊對話：最後一次說話早於期限的整段刪掉，最近還在聊的留著")
    void deletesInactiveConversations() {
        String old = UUID.randomUUID().toString();
        memory.add(old, oneTurnWithTool());
        memory.add(id, List.of(new UserMessage("嗨"), new AssistantMessage("嗨！")));
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - interval '31 days' WHERE conversation_id = :id")
                .param("id", UUID.fromString(old)).update();

        int deleted = store.deleteInactiveSince(Instant.now().minus(Duration.ofDays(30)));

        assertEquals(1, deleted);
        assertTrue(memory.get(old).isEmpty());
        assertEquals(2, memory.get(id).size());
    }

    @Test
    @DisplayName("清舊對話看的是最後一次說話：開頭很舊、最後一則是最近的，整段留著")
    void keepsConversationWithRecentMessage() {
        memory.add(id, oneTurnWithTool());
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - interval '31 days' WHERE conversation_id = :id AND position < 3")
                .param("id", UUID.fromString(id)).update();

        assertEquals(0, store.deleteInactiveSince(Instant.now().minus(Duration.ofDays(30))));
        assertEquals(4, memory.get(id).size());
    }

    @Test
    @DisplayName("沒存過的對話：空的")
    void unknownConversation() {
        assertTrue(memory.get(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    @DisplayName("#104 別人拿著同一個 conversationId：讀到的是空的；存、刪都只動到他自己名下的，碰不到我的那段")
    void conversationsAreSeparatedByOwner() {
        memory.add(id, oneTurnWithTool());
        ChatMemory other = store.of(OTHER);

        assertTrue(other.get(id).isEmpty());
        other.add(id, List.of(new UserMessage("蓋掉你")));
        other.clear(id);

        assertEquals(4, memory.get(id).size());
    }

    @Test
    @DisplayName("#104 兩個人剛好同一個 conversationId，清舊對話也各算各的：只刪很久沒說話的那個人的")
    void cleanupCountsEachOwnersConversation() {
        memory.add(id, oneTurnWithTool());
        store.of(OTHER).add(id, List.of(new UserMessage("嗨")));
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - interval '31 days' WHERE owner_id = :owner")
                .param("owner", OWNER.value()).update();

        assertEquals(1, store.deleteInactiveSince(Instant.now().minus(Duration.ofDays(30))));
        assertTrue(memory.get(id).isEmpty());
        assertEquals(1, store.of(OTHER).get(id).size());
    }

    @Test
    @DisplayName("資料庫也不收沒有擁有者的訊息（V11 的 NOT NULL）")
    void ownerIsRequired() {
        assertThrows(Exception.class, () -> jdbc.sql("""
                        INSERT INTO calendar_assistant_message (conversation_id, position, type, content)
                        VALUES (:id, 0, 'USER', '嗨')
                        """).param("id", UUID.fromString(id)).update());
    }

    @Test
    @DisplayName("資料庫出事：三個方法都丟 IllegalStateException（502），不讓 Spring 的例外往外跑")
    void databaseFailuresAreUpstreamFailures() {
        // 連到一個沒有資料庫的埠：每一次查詢都會失敗
        DriverManagerDataSource nowhere = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "x");
        ChatMemory broken = new JdbcAssistantMemoryRepository(JdbcClient.create(nowhere),
                new TransactionTemplate(new DataSourceTransactionManager(nowhere)), KEEP_TURNS).of(OWNER);

        assertThrows(IllegalStateException.class, () -> broken.get(id));
        assertThrows(IllegalStateException.class, () -> broken.add(id, List.of(new UserMessage("嗨"))));
        assertThrows(IllegalStateException.class, () -> broken.clear(id));
    }

    @Test
    @DisplayName("資料庫的 CHECK：工具結果只能放在 TOOL、工具呼叫只能放在 ASSISTANT")
    void databaseChecks() {
        assertThrows(Exception.class, () -> jdbc.sql("""
                        INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content, tool_calls)
                        VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, 0, 'USER', '嗨', '[]'::jsonb)
                        """).param("id", UUID.fromString(id)).update());
        assertThrows(Exception.class, () -> jdbc.sql("""
                        INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content)
                        VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, 0, 'SYSTEM', '你是助理')
                        """).param("id", UUID.fromString(id)).update());
    }
}
