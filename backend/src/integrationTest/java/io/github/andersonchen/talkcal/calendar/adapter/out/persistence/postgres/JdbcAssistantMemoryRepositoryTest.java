package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    static JdbcAssistantMemoryRepository memory;
    static JdbcClient jdbc;

    private final String id = UUID.randomUUID().toString();

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        memory = new JdbcAssistantMemoryRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
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
    @DisplayName("清舊對話：最後一次說話早於期限的整段刪掉，最近還在聊的留著")
    void deletesInactiveConversations() {
        String old = UUID.randomUUID().toString();
        memory.saveAll(old, oneTurnWithTool());
        memory.saveAll(id, List.of(new UserMessage("嗨"), new AssistantMessage("嗨！")));
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - interval '31 days' WHERE conversation_id = :id")
                .param("id", UUID.fromString(old)).update();

        int deleted = memory.deleteInactiveSince(Instant.now().minus(Duration.ofDays(30)));

        assertEquals(1, deleted);
        assertTrue(memory.findByConversationId(old).isEmpty());
        assertEquals(2, memory.findByConversationId(id).size());
    }

    @Test
    @DisplayName("清舊對話看的是最後一次說話：開頭很舊、最後一則是最近的，整段留著")
    void keepsConversationWithRecentMessage() {
        memory.saveAll(id, oneTurnWithTool());
        jdbc.sql("UPDATE calendar_assistant_message SET created_at = now() - interval '31 days' WHERE conversation_id = :id AND position < 3")
                .param("id", UUID.fromString(id)).update();

        assertEquals(0, memory.deleteInactiveSince(Instant.now().minus(Duration.ofDays(30))));
        assertEquals(4, memory.findByConversationId(id).size());
    }

    @Test
    @DisplayName("沒存過的對話：空的")
    void unknownConversation() {
        assertTrue(memory.findByConversationId(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    @DisplayName("資料庫的 CHECK：工具結果只能放在 TOOL、工具呼叫只能放在 ASSISTANT")
    void databaseChecks() {
        assertThrows(Exception.class, () -> jdbc.sql("""
                        INSERT INTO calendar_assistant_message (conversation_id, position, type, content, tool_calls)
                        VALUES (:id, 0, 'USER', '嗨', '[]'::jsonb)
                        """).param("id", UUID.fromString(id)).update());
        assertThrows(Exception.class, () -> jdbc.sql("""
                        INSERT INTO calendar_assistant_message (conversation_id, position, type, content)
                        VALUES (:id, 0, 'SYSTEM', '你是助理')
                        """).param("id", UUID.fromString(id)).update());
    }
}
