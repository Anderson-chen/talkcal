package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataAccessException;
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
 * AI 助理的對話記憶，連工具呼叫一起存（表在 V7__create_calendar_assistant_message.sql，為什麼另開一張表也寫在那裡）。
 *
 * 對話依擁有者分開（V11）：每一句 SQL 都帶著 owner_id，同一個 conversation_id 在不同人底下是兩段不同的對話，
 * 拿到別人的 conversation_id 也讀不到、蓋不掉那個人的對話。
 *
 * 寫入只往後接，不整段取代。照 Spring AI ChatMemoryRepository 的 saveAll 寫的話，是讀出整段、呼叫模型（要好幾秒）、
 * 整段刪掉重插：同一段對話同時送兩句時（例如開兩個分頁、按了兩次送出），兩個請求讀到同一份歷史，晚存的整段蓋掉早的，
 * 早的那句和回覆就不見了，而且沒有任何錯誤。每一輪只把這一輪新增的那幾則接在最後面，兩句都會留著。
 *
 * 不用樂觀鎖（version 欄位、衝突回 409）：兩句都是使用者真的說過的，沒有哪一句該被退回；
 * 回 409 要使用者重送，重送又要再呼叫一次模型，多等好幾秒、多花一次錢，結果跟直接接上去一樣。
 * 代價：模型回覆晚到的那句時，看不到同時送出的另一句；但兩句和兩個回覆都存著，下一輪模型都看得到。
 *
 * of(owner) 回傳 Spring AI 的 ChatMemory，不另外定 port：對話記憶只在 AI 助理（inbound adapter）和框架之間使用，
 * core 裡沒有對話的概念。用 ChatMemory 不用 ChatMemoryRepository：框架裡往後接的語意是 ChatMemory.add，
 * ChatMemoryRepository 只有 saveAll（整段取代），照 saveAll 寫一定是讀、改、寫，避不開上面的遺失更新。
 * 框架的介面只收 conversationId，擁有者要在取得這個物件之前決定，所以是先 of(owner)，再照框架的方法用。
 *
 * 名字不叫 Adapter：這個字尾在專案裡專指實作 core outbound port 的類別（ArchitectureTest 會檢查），這個類別不是。
 *
 * 只存 USER、ASSISTANT、TOOL：system 每一輪由程式重新給（日期表要跟著「現在」變），傳進來也不存。
 */
public final class JdbcAssistantMemoryRepository {

    // 設定好之後是執行緒安全的，可以當常數重用
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final int keepTurns;

    /**
     * @param keepTurns 一段對話最多留幾輪（一輪是使用者一句話，加上之後的工具呼叫和回覆）。每接上新的一輪，就刪掉更早的輪：
     *                  只往後接的話，一直在聊的對話不會被歸檔排程搬走，會無限變長
     */
    public JdbcAssistantMemoryRepository(JdbcClient jdbc, TransactionTemplate transaction, int keepTurns) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
        if (keepTurns < 1) {
            throw new IllegalArgumentException("keepTurns 至少要 1：" + keepTurns);
        }
        this.keepTurns = keepTurns;
    }

    /** 物件很輕，只記著擁有者，每個請求拿一個新的就好。 */
    public ChatMemory of(OwnerId owner) {
        return new OwnedMemory(Objects.requireNonNull(owner, "owner 不可為 null").value());
    }

    /**
     * cutoff 之前就沒再說過話的對話，最多 limit 段，最舊的先。不分擁有者：這是這張表自己的維護工作。
     *
     * 最後說話的時間是那段對話最新一列的 created_at：寫入只往後接，最新一列就是最後那一輪存進來的時間。
     * 整段一起拿，不會歸檔到只剩後半截的對話。(owner_id, conversation_id) 才是一段對話，兩個人剛好用了同一個 conversation_id 也各算各的。
     *
     * 讀出來的是資料庫裡原本的樣子（工具呼叫是 JSON 字串、帶著 created_at），不轉成 Spring AI 的 Message：
     * 歸檔要原封不動的備份，還原時照原樣插回去，不必經過框架的型別再轉一次。
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
        // SQL 已經依對話排好，同一段的訊息連在一起：換了對話就收起前一段
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
     * 只在這段對話現在還是 cutoff 之前就沒說過話時才刪，回傳有沒有刪。
     *
     * 擋的是讀出來到上傳完的那幾秒之間，使用者剛好又接著聊了：那段已經不舊了，刪掉就是刪掉正在聊的對話。
     * 條件和刪除在同一句 SQL，而且先取得這段對話的鎖，跟接上新一輪的 add 排隊：
     * 不排隊的話看不到還沒 commit 的新一輪，舊的整段被刪掉，線上只剩最新一輪，之後還原也會因為已經有了而跳過。
     */
    public boolean deleteIfInactiveSince(OwnerId owner, UUID conversationId, Instant cutoff) {
        try {
            Boolean deleted = transaction.execute(status -> {
                lock(owner.value(), conversationId);
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
            });
            return Boolean.TRUE.equals(deleted);
        } catch (DataAccessException | TransactionException e) {
            throw new IllegalStateException("刪除已歸檔的對話失敗：" + conversationId, e);
        }
    }

    /**
     * 照原樣插回去（連 created_at 一起），回傳有沒有放回去。
     *
     * 這個人名下已經有同一個 conversation_id 的對話（還原過、或還沒被刪）就整段跳過：線上那份可能比歸檔新，
     * 不能拿舊的蓋掉；跳過也讓還原按幾次結果都一樣。先取得這段對話的鎖，跟 add 和其他還原排隊，
     * 查有沒有到插入之間不會有別的寫入插進來撞主鍵。
     */
    public boolean restore(StoredConversation conversation) {
        try {
            Boolean restored = transaction.execute(status -> {
                lock(conversation.owner().value(), conversation.conversationId());
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
        } catch (DataAccessException | TransactionException e) {
            throw new IllegalStateException("還原 AI 助理的對話失敗：" + conversation.conversationId(), e);
        }
    }

    // 鎖 (擁有者, 對話) 這一組，交易結束自動釋放；hashtextextended 把 key 轉成 advisory lock 要的 bigint。
    // 不同對話剛好撞到同一個雜湊值，只是多排一下隊。這段對話的每一種寫入（接上、歸檔刪除、還原）都要先取得
    private void lock(UUID owner, UUID conversationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "calendar_assistant_message:" + owner + ":" + conversationId)
                .query((rs, rowNum) -> 0)
                .list();
    }

    /** 歸檔、還原用。lastActive 是最後一次說話的時間。 */
    public record StoredConversation(OwnerId owner, UUID conversationId, Instant lastActive, List<StoredMessage> messages) {
    }

    /** toolCalls、toolResponses 是 JSONB 的原文，沒有就是 null。 */
    public record StoredMessage(int position, String type, String content, String toolCalls, String toolResponses,
                                Instant createdAt) {
    }

    private record StoredRow(OwnerId owner, UUID conversationId, Instant lastActive, StoredMessage message) {

        boolean sameConversation(StoredRow other) {
            return owner.equals(other.owner) && conversationId.equals(other.conversationId);
        }
    }

    private final class OwnedMemory implements ChatMemory {

        private final UUID owner;

        OwnedMemory(UUID owner) {
            this.owner = owner;
        }

        @Override
        public List<Message> get(String conversationId) {
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
         * 包在一個交易裡，不會留下插了一半的一輪。
         *
         * 接在哪裡（position）要先查目前最後一則是第幾則；兩個請求同時查會拿到同一個號碼，第二個撞主鍵失敗。
         * 所以先取得這段對話的 advisory lock，同一段對話的寫入排隊、一次一個。鎖只包查號碼、插入、刪舊輪這幾句 SQL，
         * 呼叫模型的那幾秒不在裡面，不同的對話也不互相等。
         * 不用 SELECT ... FOR UPDATE：第一輪還沒有任何一列可以鎖，兩個請求還是會撞。
         */
        @Override
        public void add(String conversationId, List<Message> messages) {
            UUID id = uuid(conversationId);
            List<Message> stored = messages.stream().filter(m -> m.getMessageType() != MessageType.SYSTEM).toList();
            if (stored.isEmpty()) {
                return;
            }
            try {
                transaction.executeWithoutResult(status -> {
                    lock(owner, id);
                    int next = jdbc.sql("""
                                    SELECT coalesce(max(position) + 1, 0) FROM calendar_assistant_message
                                    WHERE owner_id = :owner AND conversation_id = :id
                                    """)
                            .param("owner", owner)
                            .param("id", id)
                            .query(Integer.class)
                            .single();
                    for (int i = 0; i < stored.size(); i++) {
                        insert(id, next + i, stored.get(i));
                    }
                    dropOldTurns(id);
                });
            } catch (DataAccessException | TransactionException e) {
                // 連不上資料庫時，失敗發生在開始交易那一步，丟的是 TransactionException，不是 DataAccessException
                throw new IllegalStateException("儲存 AI 助理的對話失敗：" + conversationId, e);
            }
        }

        @Override
        public void clear(String conversationId) {
            try {
                jdbc.sql("DELETE FROM calendar_assistant_message WHERE owner_id = :owner AND conversation_id = :id")
                        .param("owner", owner)
                        .param("id", uuid(conversationId))
                        .update();
            } catch (DataAccessException e) {
                throw new IllegalStateException("刪除 AI 助理的對話失敗：" + conversationId, e);
            }
        }

        /**
         * 從倒數第 keepTurns 句使用者的話開始留，更早的刪掉。以輪為單位刪、不從中間截：
         * 工具結果前面一定要有呼叫工具的那則助理訊息，拆開了 API 會拒收整個請求。
         * position 不重新編號：讀的時候只看順序。
         */
        private void dropOldTurns(UUID id) {
            jdbc.sql("""
                            DELETE FROM calendar_assistant_message
                            WHERE owner_id = :owner AND conversation_id = :id
                              AND position < (
                                  SELECT position FROM calendar_assistant_message
                                  WHERE owner_id = :owner AND conversation_id = :id AND type = 'USER'
                                  ORDER BY position DESC
                                  OFFSET :skip LIMIT 1)
                            """)
                    .param("owner", owner)
                    .param("id", id)
                    .param("skip", keepTurns - 1)
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

    // controller 已經檢查過是 UUID；這裡再轉一次是因為欄位型別是 UUID
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

    // 不直接序列化 Spring AI 的型別：Spring AI 改了欄位，舊資料照樣讀得回來
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
