package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 助理的對話記憶存 PostgreSQL，連工具呼叫一起（表在 V7__create_calendar_assistant_message.sql，為什麼另開一張表也寫在那裡）。
 *
 * 實作 Spring AI 的 ChatMemoryRepository，而不是自己定一個 port：
 * 對話記憶是 AI 助理（inbound adapter）跟框架之間的事，core 不知道有「對話」這回事。
 * 照框架的介面寫，哪天 Spring AI 官方的實作支援工具訊息了，組裝根換一行就好。
 *
 * 名字不叫 Adapter：這個字尾在專案裡專指「實作 core 的 outbound port」（ArchitectureTest 守著），這個不是。
 *
 * 只存 USER、ASSISTANT、TOOL：system 每一輪由程式重新給（日期表要跟著「現在」走），傳進來也不存。
 */
public final class JdbcAssistantMemoryRepository implements ChatMemoryRepository {

    // 設定好之後就是執行緒安全的，當常數重用
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcAssistantMemoryRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    @Override
    public List<String> findConversationIds() {
        return jdbc.sql("SELECT DISTINCT conversation_id FROM calendar_assistant_message")
                .query((rs, rowNum) -> rs.getObject(1, UUID.class).toString())
                .list();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        try {
            return jdbc.sql("""
                            SELECT type, content, tool_calls::text AS tool_calls, tool_responses::text AS tool_responses
                            FROM calendar_assistant_message
                            WHERE conversation_id = :id
                            ORDER BY position
                            """)
                    .param("id", uuid(conversationId))
                    .query(JdbcAssistantMemoryRepository::message)
                    .list();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取 AI 助理的對話失敗：" + conversationId, e);
        }
    }

    /**
     * 整段取代（Spring AI 的 ChatMemoryRepository 就是這個語意）：先刪再全部重插，包在一個交易裡，
     * 不會留下「刪了一半、插了一半」的對話。
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        UUID id = uuid(conversationId);
        List<Message> stored = messages.stream().filter(m -> m.getMessageType() != MessageType.SYSTEM).toList();
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.sql("DELETE FROM calendar_assistant_message WHERE conversation_id = :id").param("id", id).update();
                for (int position = 0; position < stored.size(); position++) {
                    insert(id, position, stored.get(position));
                }
            });
        } catch (DataAccessException e) {
            throw new IllegalStateException("儲存 AI 助理的對話失敗：" + conversationId, e);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbc.sql("DELETE FROM calendar_assistant_message WHERE conversation_id = :id").param("id", uuid(conversationId)).update();
    }

    /**
     * 刪掉 cutoff 之前就沒再說過話的對話，回傳刪了幾段。不在 ChatMemoryRepository 裡，是這張表自己的維護工作。
     *
     * 「最後說話的時間」= 那段對話最新一列的 created_at：saveAll 每一輪都整段刪掉重插，
     * 所以一段對話的每一列其實都是最後那一輪的時間。用 max 而不是隨便一列，是不想依賴這個巧合 ——
     * 哪天改成只插新的那幾則，這裡照樣對。整段一起刪，不會留下只剩後半截、開頭被砍掉的對話。
     */
    public int deleteInactiveSince(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff 不可為 null");
        return jdbc.sql("""
                        WITH inactive AS (
                            SELECT conversation_id FROM calendar_assistant_message
                            GROUP BY conversation_id
                            HAVING max(created_at) < :cutoff
                        )
                        DELETE FROM calendar_assistant_message m
                        USING inactive
                        WHERE m.conversation_id = inactive.conversation_id
                        RETURNING m.conversation_id
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .set()
                .size();
    }

    private void insert(UUID id, int position, Message message) {
        String toolCalls = null;
        String toolResponses = null;
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
            toolCalls = write(assistant.getToolCalls().stream().map(ToolCallJson::of).toList());
        }
        if (message instanceof ToolResponseMessage tool) {
            toolResponses = write(tool.getResponses().stream().map(ToolResponseJson::of).toList());
        }
        jdbc.sql("""
                        INSERT INTO calendar_assistant_message (conversation_id, position, type, content, tool_calls, tool_responses)
                        VALUES (:id, :position, :type, :content, CAST(:toolCalls AS jsonb), CAST(:toolResponses AS jsonb))
                        """)
                .param("id", id)
                .param("position", position)
                .param("type", message.getMessageType().name())
                .param("content", message.getText() == null ? "" : message.getText())
                .param("toolCalls", toolCalls)
                .param("toolResponses", toolResponses)
                .update();
    }

    private static Message message(ResultSet rs, int rowNum) throws SQLException {
        String content = rs.getString("content");
        return switch (rs.getString("type")) {
            case "USER" -> new UserMessage(content);
            case "ASSISTANT" -> AssistantMessage.builder()
                    .content(content)
                    .toolCalls(rs.getString("tool_calls") == null ? List.of()
                            : read(rs.getString("tool_calls"), new TypeReference<List<ToolCallJson>>() { })
                                    .stream().map(ToolCallJson::toToolCall).toList())
                    .build();
            case "TOOL" -> ToolResponseMessage.builder()
                    .responses(read(rs.getString("tool_responses"), new TypeReference<List<ToolResponseJson>>() { })
                            .stream().map(ToolResponseJson::toToolResponse).toList())
                    .build();
            default -> throw new IllegalStateException("資料庫裡有不認得的訊息類型：" + rs.getString("type"));
        };
    }

    // conversationId 由 controller 驗過是 UUID；這裡再轉一次是因為欄位型別是 UUID
    private static UUID uuid(String conversationId) {
        return UUID.fromString(Objects.requireNonNull(conversationId, "conversationId 不可為 null"));
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("工具訊息寫成 JSON 失敗", e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("資料庫裡的工具訊息讀不懂：" + json, e);
        }
    }

    // 存進 JSONB 的形狀：自己的 record，不直接序列化 Spring AI 的型別 —— 它改了欄位，舊資料照樣讀得回來
    record ToolCallJson(String id, String type, String name, String arguments) {

        static ToolCallJson of(AssistantMessage.ToolCall call) {
            return new ToolCallJson(call.id(), call.type(), call.name(), call.arguments());
        }

        AssistantMessage.ToolCall toToolCall() {
            return new AssistantMessage.ToolCall(id, type, name, arguments);
        }
    }

    record ToolResponseJson(String id, String name, String responseData) {

        static ToolResponseJson of(ToolResponseMessage.ToolResponse response) {
            return new ToolResponseJson(response.id(), response.name(), response.responseData());
        }

        ToolResponseMessage.ToolResponse toToolResponse() {
            return new ToolResponseMessage.ToolResponse(id, name, responseData);
        }
    }
}
