package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 助理的對話記憶存 PostgreSQL，連工具呼叫一起（表在 V7__create_calendar_assistant_message.sql，為什麼另開一張表也寫在那裡）。
 *
 * 對話依擁有者分開（#104，V11）：of(owner) 拿到的是「這個人名下的對話」，每一句 SQL 都帶著 owner_id。
 * 同一個 conversation_id 在不同人底下是不同的兩段，別人拿到你的 conversation_id 也讀不到、蓋不掉你的那段。
 *
 * of(owner) 回傳 Spring AI 的 ChatMemoryRepository，而不是自己定一個 port：
 * 對話記憶是 AI 助理（inbound adapter）跟框架之間的事，core 不知道有「對話」這回事。
 * 照框架的介面寫，哪天 Spring AI 官方的實作支援工具訊息了，組裝根換一行就好。
 * 框架的介面只認 conversationId，擁有者只好在拿到這個物件之前先決定 —— 所以是「先 of(owner)，再照框架的方法用」。
 *
 * 名字不叫 Adapter：這個字尾在專案裡專指「實作 core 的 outbound port」（ArchitectureTest 守著），這個不是。
 *
 * 只存 USER、ASSISTANT、TOOL：system 每一輪由程式重新給（日期表要跟著「現在」走），傳進來也不存。
 */
public final class JdbcAssistantMemoryRepository {

    // 設定好之後就是執行緒安全的，當常數重用
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcAssistantMemoryRepository(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
    }

    /** 這個人名下的對話。物件很輕（只記著擁有者），每個請求拿一個新的就好。 */
    public ChatMemoryRepository of(OwnerId owner) {
        return new OwnedMemory(Objects.requireNonNull(owner, "owner 不可為 null").value());
    }

    /**
     * 找出 cutoff 之前就沒再說過話的對話（#54：歸檔之後才刪，見 app.job.AssistantMemoryArchive），最多 limit 段，最舊的先。
     * 不分擁有者：這是這張表自己的維護工作，不是誰的請求。
     *
     * 「最後說話的時間」= 那段對話最新一列的 created_at：saveAll 每一輪都整段刪掉重插，
     * 所以一段對話的每一列其實都是最後那一輪的時間。用 max 而不是隨便一列，是不想依賴這個巧合 ——
     * 哪天改成只插新的那幾則，這裡照樣對。整段一起拿，不會歸檔到只剩後半截、開頭被砍掉的對話。
     * 一段對話是 (owner_id, conversation_id) 一組：兩個人剛好用了同一個 conversation_id，也是各算各的。
     *
     * 讀出來的是資料庫裡原本的樣子（工具呼叫是 JSON 字串、帶著 created_at），不翻成 Spring AI 的 Message：
     * 歸檔要的是「原封不動的備份」，還原時照原樣插回去，不必經過框架的型別再翻一次。
     */
    public List<StoredConversation> findInactiveSince(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "cutoff 不可為 null");
        List<StoredRow> rows;
        try {
            rows = jdbc.sql("""
                            WITH inactive AS (
                                SELECT owner_id, conversation_id, max(created_at) AS last_active
                                FROM calendar_assistant_message
                                GROUP BY owner_id, conversation_id
                                HAVING max(created_at) < :cutoff
                                ORDER BY last_active, owner_id, conversation_id
                                LIMIT :limit
                            )
                            SELECT m.owner_id, m.conversation_id, inactive.last_active, m.position, m.type, m.content,
                                   m.tool_calls::text AS tool_calls, m.tool_responses::text AS tool_responses, m.created_at
                            FROM calendar_assistant_message m
                            JOIN inactive USING (owner_id, conversation_id)
                            ORDER BY inactive.last_active, m.owner_id, m.conversation_id, m.position
                            """)
                    .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                    .param("limit", limit)
                    .query((rs, rowNum) -> new StoredRow(
                            new OwnerId(rs.getObject("owner_id", UUID.class)), rs.getObject("conversation_id", UUID.class),
                            rs.getObject("last_active", OffsetDateTime.class).toInstant(),
                            new StoredMessage(rs.getInt("position"), rs.getString("type"), rs.getString("content"),
                                    rs.getString("tool_calls"), rs.getString("tool_responses"),
                                    rs.getObject("created_at", OffsetDateTime.class).toInstant())))
                    .list();
        } catch (DataAccessException e) {
            throw new IllegalStateException("讀取要歸檔的對話失敗", e);
        }
        // 一列一則訊息 → 一段對話一個物件。SQL 已經依對話排好，同一段的訊息連在一起：換了對話就收起前一段
        List<StoredConversation> conversations = new ArrayList<>();
        List<StoredMessage> messages = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            StoredRow row = rows.get(i);
            messages.add(row.message());
            boolean last = i == rows.size() - 1 || !rows.get(i + 1).sameConversation(row);
            if (last) {
                conversations.add(new StoredConversation(row.owner(), row.conversationId(), row.lastActive(), List.copyOf(messages)));
                messages.clear();
            }
        }
        return conversations;
    }

    /**
     * 刪掉一段已經歸檔好的對話，但只在它「現在」還是 cutoff 之前就沒說過話的時候。回傳有沒有刪。
     *
     * 這個條件擋的是：讀出來、上傳的那幾秒之間，使用者剛好又接著聊了 —— 那段已經不舊了，刪掉就是刪掉正在聊的對話。
     * 條件和刪除在同一句 SQL 裡，不是先查再刪。
     */
    public boolean deleteIfInactiveSince(OwnerId owner, UUID conversationId, Instant cutoff) {
        try {
            return jdbc.sql("""
                            DELETE FROM calendar_assistant_message
                            WHERE owner_id = :owner AND conversation_id = :id
                              AND (SELECT max(created_at) FROM calendar_assistant_message
                                   WHERE owner_id = :owner AND conversation_id = :id) < :cutoff
                            """)
                    .param("owner", owner.value())
                    .param("id", conversationId)
                    .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                    .update() > 0;
        } catch (DataAccessException e) {
            throw new IllegalStateException("刪除已歸檔的對話失敗：" + conversationId, e);
        }
    }

    /**
     * 把歸檔裡的一段對話照原樣插回去（連 created_at 一起）。回傳有沒有放回去。
     *
     * 這個人名下已經有同一個 conversation_id 的對話（還原過、或還沒被刪）就整段跳過：
     * 線上那份可能比歸檔新，不能拿舊的蓋掉；跳過也讓還原按幾次結果都一樣。
     */
    public boolean restore(StoredConversation conversation) {
        try {
            Boolean restored = transaction.execute(status -> {
                boolean exists = jdbc.sql("""
                                SELECT EXISTS (SELECT 1 FROM calendar_assistant_message
                                               WHERE owner_id = :owner AND conversation_id = :id)
                                """)
                        .param("owner", conversation.owner().value())
                        .param("id", conversation.conversationId())
                        .query(Boolean.class)
                        .single();
                if (exists) {
                    return false;
                }
                for (StoredMessage m : conversation.messages()) {
                    jdbc.sql("""
                                    INSERT INTO calendar_assistant_message
                                        (owner_id, conversation_id, position, type, content, tool_calls, tool_responses, created_at)
                                    VALUES (:owner, :id, :position, :type, :content,
                                            CAST(:toolCalls AS jsonb), CAST(:toolResponses AS jsonb), :createdAt)
                                    """)
                            .param("owner", conversation.owner().value())
                            .param("id", conversation.conversationId())
                            .param("position", m.position())
                            .param("type", m.type())
                            .param("content", m.content())
                            .param("toolCalls", m.toolCalls())
                            .param("toolResponses", m.toolResponses())
                            .param("createdAt", OffsetDateTime.ofInstant(m.createdAt(), ZoneOffset.UTC))
                            .update();
                }
                return true;
            });
            return Boolean.TRUE.equals(restored);
        } catch (DuplicateKeyException e) {
            // 查完「沒有」到插進去之間，同一段被別人放進去了（兩個還原請求同時跑、或使用者剛好用這個 id 說話）：
            // 交易整個 rollback，結果跟「已經在了」一樣，跳過，不當成錯誤
            return false;
        } catch (DataAccessException | TransactionException e) {
            throw new IllegalStateException("還原 AI 助理的對話失敗：" + conversation.conversationId(), e);
        }
    }

    /** 一段對話在資料庫裡原本的樣子（歸檔、還原用）。lastActive 是最後一次說話的時間。 */
    public record StoredConversation(OwnerId owner, UUID conversationId, Instant lastActive, List<StoredMessage> messages) {
    }

    /** 一則訊息在資料庫裡原本的樣子。toolCalls、toolResponses 是 JSONB 的原文，沒有就是 null。 */
    public record StoredMessage(int position, String type, String content, String toolCalls, String toolResponses,
                                Instant createdAt) {
    }

    private record StoredRow(OwnerId owner, UUID conversationId, Instant lastActive, StoredMessage message) {

        boolean sameConversation(StoredRow other) {
            return owner.equals(other.owner) && conversationId.equals(other.conversationId);
        }
    }

    private final class OwnedMemory implements ChatMemoryRepository {

        private final UUID owner;

        OwnedMemory(UUID owner) {
            this.owner = owner;
        }

        @Override
        public List<String> findConversationIds() {
            try {
                return jdbc.sql("SELECT DISTINCT conversation_id FROM calendar_assistant_message WHERE owner_id = :owner")
                        .param("owner", owner)
                        .query((rs, rowNum) -> rs.getObject(1, UUID.class).toString())
                        .list();
            } catch (DataAccessException e) {
                throw new IllegalStateException("讀取 AI 助理的對話清單失敗", e);
            }
        }

        @Override
        public List<Message> findByConversationId(String conversationId) {
            try {
                return jdbc.sql("""
                                SELECT type, content, tool_calls::text AS tool_calls, tool_responses::text AS tool_responses
                                FROM calendar_assistant_message
                                WHERE owner_id = :owner AND conversation_id = :id
                                ORDER BY position
                                """)
                        .param("owner", owner)
                        .param("id", uuid(conversationId))
                        .query(JdbcAssistantMemoryRepository::message)
                        .list();
            } catch (DataAccessException e) {
                throw new IllegalStateException("讀取 AI 助理的對話失敗：" + conversationId, e);
            }
        }

        /**
         * 整段取代（Spring AI 的 ChatMemoryRepository 就是這個語意）：先刪再全部重插，包在一個交易裡，
         * 不會留下「刪了一半、插了一半」的對話。刪的也只有這個人名下的那段。
         */
        @Override
        public void saveAll(String conversationId, List<Message> messages) {
            UUID id = uuid(conversationId);
            List<Message> stored = messages.stream().filter(m -> m.getMessageType() != MessageType.SYSTEM).toList();
            try {
                transaction.executeWithoutResult(status -> {
                    delete(id);
                    for (int position = 0; position < stored.size(); position++) {
                        insert(id, position, stored.get(position));
                    }
                });
            } catch (DataAccessException | TransactionException e) {
                // 連不上資料庫時，失敗發生在開交易那一步，丟的是 TransactionException，不是 DataAccessException
                throw new IllegalStateException("儲存 AI 助理的對話失敗：" + conversationId, e);
            }
        }

        @Override
        public void deleteByConversationId(String conversationId) {
            try {
                delete(uuid(conversationId));
            } catch (DataAccessException e) {
                throw new IllegalStateException("刪除 AI 助理的對話失敗：" + conversationId, e);
            }
        }

        private void delete(UUID id) {
            jdbc.sql("DELETE FROM calendar_assistant_message WHERE owner_id = :owner AND conversation_id = :id")
                    .param("owner", owner)
                    .param("id", id)
                    .update();
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
                            INSERT INTO calendar_assistant_message
                                (owner_id, conversation_id, position, type, content, tool_calls, tool_responses)
                            VALUES (:owner, :id, :position, :type, :content, CAST(:toolCalls AS jsonb), CAST(:toolResponses AS jsonb))
                            """)
                    .param("owner", owner)
                    .param("id", id)
                    .param("position", position)
                    .param("type", message.getMessageType().name())
                    .param("content", message.getText() == null ? "" : message.getText())
                    .param("toolCalls", toolCalls)
                    .param("toolResponses", toolResponses)
                    .update();
        }
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
