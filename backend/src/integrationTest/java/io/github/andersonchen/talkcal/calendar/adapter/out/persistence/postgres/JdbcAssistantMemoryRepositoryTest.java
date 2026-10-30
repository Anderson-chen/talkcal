package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository.StoredConversation;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository.StoredMessage;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
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

    static JdbcAssistantMemoryRepository store;
    static JdbcClient jdbc;

    private final String id = UUID.randomUUID().toString();
    // 大部分的測試只看一個人：OWNER 名下的對話
    private final ChatMemoryRepository memory = store.of(OWNER);

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcAssistantMemoryRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
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
        memory.saveAll(id, oneTurnWithTool());

        List<Message> read = memory.findByConversationId(id);

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
    @DisplayName("saveAll 是整段取代：存第二次，第一次的不會留著")
    void saveAllReplaces() {
        memory.saveAll(id, oneTurnWithTool());
        memory.saveAll(id, List.of(new UserMessage("嗨"), new AssistantMessage("嗨！")));

        assertEquals(List.of("嗨", "嗨！"), memory.findByConversationId(id).stream().map(Message::getText).toList());
    }

    @Test
    @DisplayName("system 不存（每一輪由程式重新給）")
    void systemIsNotStored() {
        memory.saveAll(id, List.of(new SystemMessage("你是助理"), new UserMessage("嗨"), new AssistantMessage("嗨！")));

        assertEquals(2, memory.findByConversationId(id).size());
    }

    @Test
    @DisplayName("對話之間互不干擾；找得到有哪些對話；刪掉一段只刪那段")
    void conversationsAreSeparate() {
        String other = UUID.randomUUID().toString();
        memory.saveAll(id, oneTurnWithTool());
        memory.saveAll(other, List.of(new UserMessage("另一段")));

        assertTrue(memory.findConversationIds().containsAll(List.of(id, other)));
        memory.deleteByConversationId(id);

        assertTrue(memory.findByConversationId(id).isEmpty());
        assertEquals(1, memory.findByConversationId(other).size());
    }

    @Test
    @DisplayName("#54 找要歸檔的對話：最後一次說話早於期限的整段拿出來（連工具呼叫的 JSON 原文），最近還在聊的不拿")
    void findsInactiveConversations() {
        String old = UUID.randomUUID().toString();
        memory.saveAll(old, oneTurnWithTool());
        memory.saveAll(id, List.of(new UserMessage("嗨"), new AssistantMessage("嗨！")));
        makeOld(OWNER, old, 31);

        List<StoredConversation> found = store.findInactiveSince(cutoff(), 100);

        assertEquals(1, found.size());
        StoredConversation conversation = found.getFirst();
        assertEquals(OWNER, conversation.owner());
        assertEquals(UUID.fromString(old), conversation.conversationId());
        assertEquals(List.of(0, 1, 2, 3), conversation.messages().stream().map(StoredMessage::position).toList());
        assertTrue(conversation.messages().get(1).toolCalls().contains("propose_events"));
        assertTrue(conversation.messages().get(2).toolResponses().contains("已顯示 1 張卡片"));
    }

    @Test
    @DisplayName("#54 反例：開頭很舊、最後一則是最近的，不算舊 —— 不拿、也刪不掉")
    void conversationWithRecentMessageIsNotOld() {
        memory.saveAll(id, oneTurnWithTool());
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - interval '31 days' WHERE conversation_id = :id AND position < 3")
                .param("id", UUID.fromString(id)).update();

        assertTrue(store.findInactiveSince(cutoff(), 100).isEmpty());
        assertFalse(store.deleteIfInactiveSince(OWNER, UUID.fromString(id), cutoff()));
        assertEquals(4, memory.findByConversationId(id).size());
    }

    @Test
    @DisplayName("#54 歸檔好之後刪：還是舊的就刪；讀出來之後使用者又接著聊了（變新了）就不刪")
    void deletesOnlyIfStillInactive() {
        String resumed = UUID.randomUUID().toString();
        memory.saveAll(id, oneTurnWithTool());
        memory.saveAll(resumed, List.of(new UserMessage("嗨")));
        makeOld(OWNER, id, 31);
        makeOld(OWNER, resumed, 31);
        assertEquals(2, store.findInactiveSince(cutoff(), 100).size());
        // 歸檔讀出來之後、刪之前，使用者回來接著聊（saveAll 整段重插，created_at 變成現在）
        memory.saveAll(resumed, List.of(new UserMessage("嗨"), new AssistantMessage("又見面了")));

        assertTrue(store.deleteIfInactiveSince(OWNER, UUID.fromString(id), cutoff()));
        assertFalse(store.deleteIfInactiveSince(OWNER, UUID.fromString(resumed), cutoff()));

        assertTrue(memory.findByConversationId(id).isEmpty());
        assertEquals(2, memory.findByConversationId(resumed).size());
    }

    @Test
    @DisplayName("#54 還原：照原樣插回去，讀出來跟歸檔前一樣（工具呼叫一個欄位都不少、時間也還是舊的）；線上已經有的同一段不蓋掉")
    void restoresConversationAsItWas() {
        memory.saveAll(id, oneTurnWithTool());
        makeOld(OWNER, id, 31);
        List<Message> before = memory.findByConversationId(id);
        StoredConversation archived = store.findInactiveSince(cutoff(), 100).getFirst();
        assertTrue(store.deleteIfInactiveSince(OWNER, UUID.fromString(id), cutoff()));

        assertTrue(store.restore(archived));

        assertEquals(before.toString(), memory.findByConversationId(id).toString());
        assertEquals(archived, store.findInactiveSince(cutoff(), 100).getFirst());
        // 再還原一次：已經在了，跳過，不會多出一份
        assertFalse(store.restore(archived));
        assertEquals(4, memory.findByConversationId(id).size());
    }

    @Test
    @DisplayName("#54 反例：線上那段比歸檔新（使用者後來又聊了），還原不拿舊的蓋掉")
    void restoreDoesNotOverwriteNewerConversation() {
        memory.saveAll(id, oneTurnWithTool());
        makeOld(OWNER, id, 31);
        StoredConversation archived = store.findInactiveSince(cutoff(), 100).getFirst();
        memory.saveAll(id, List.of(new UserMessage("新的")));

        assertFalse(store.restore(archived));
        assertEquals(1, memory.findByConversationId(id).size());
    }

    @Test
    @DisplayName("沒存過的對話：空的")
    void unknownConversation() {
        assertTrue(memory.findByConversationId(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    @DisplayName("#104 別人拿著同一個 conversationId：讀到的是空的；存、刪都只動到他自己名下的，碰不到我的那段")
    void conversationsAreSeparatedByOwner() {
        memory.saveAll(id, oneTurnWithTool());
        ChatMemoryRepository other = store.of(OTHER);

        assertTrue(other.findByConversationId(id).isEmpty());
        assertTrue(other.findConversationIds().isEmpty());
        other.saveAll(id, List.of(new UserMessage("蓋掉你")));
        other.deleteByConversationId(id);

        assertEquals(4, memory.findByConversationId(id).size());
        assertEquals(List.of(id), memory.findConversationIds());
    }

    @Test
    @DisplayName("#104 兩個人剛好同一個 conversationId，歸檔也各算各的：只拿、只刪很久沒說話的那個人的")
    void archivingCountsEachOwnersConversation() {
        memory.saveAll(id, oneTurnWithTool());
        store.of(OTHER).saveAll(id, List.of(new UserMessage("嗨")));
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - interval '31 days' WHERE owner_id = :owner")
                .param("owner", OWNER.value()).update();

        List<StoredConversation> found = store.findInactiveSince(cutoff(), 100);
        assertEquals(List.of(OWNER), found.stream().map(StoredConversation::owner).toList());
        assertFalse(store.deleteIfInactiveSince(OTHER, UUID.fromString(id), cutoff()));
        assertTrue(store.deleteIfInactiveSince(OWNER, UUID.fromString(id), cutoff()));

        assertTrue(memory.findByConversationId(id).isEmpty());
        assertEquals(1, store.of(OTHER).findByConversationId(id).size());
    }

    private static Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(30));
    }

    private static void makeOld(OwnerId owner, String conversationId, int days) {
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - make_interval(days => :days) "
                        + "WHERE owner_id = :owner AND conversation_id = :id")
                .param("days", days).param("owner", owner.value()).param("id", UUID.fromString(conversationId)).update();
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
    @DisplayName("資料庫出事：四個方法都丟 IllegalStateException（502），不讓 Spring 的例外往外跑")
    void databaseFailuresAreUpstreamFailures() {
        // 連到一個沒有資料庫的埠：每一次查詢都會失敗
        DriverManagerDataSource nowhere = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "x");
        ChatMemoryRepository broken = new JdbcAssistantMemoryRepository(JdbcClient.create(nowhere),
                new TransactionTemplate(new DataSourceTransactionManager(nowhere))).of(OWNER);

        assertThrows(IllegalStateException.class, broken::findConversationIds);
        assertThrows(IllegalStateException.class, () -> broken.findByConversationId(id));
        assertThrows(IllegalStateException.class, () -> broken.saveAll(id, List.of(new UserMessage("嗨"))));
        assertThrows(IllegalStateException.class, () -> broken.deleteByConversationId(id));
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
