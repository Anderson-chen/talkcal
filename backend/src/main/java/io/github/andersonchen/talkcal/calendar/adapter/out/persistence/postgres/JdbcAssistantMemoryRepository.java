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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 助理的對話記憶存 PostgreSQL，連工具呼叫一起（表在 V7__create_calendar_assistant_message.sql，為什麼另開一張表也寫在那裡）。
 *
 * 對話依擁有者分開（#104，V11）：of(owner) 拿到的是「這個人名下的對話」，每一句 SQL 都帶著 owner_id。
 * 同一個 conversation_id 在不同人底下是不同的兩段，別人拿到你的 conversation_id 也讀不到、蓋不掉你的那段。
 *
 * 寫入是只往後接（append-only），不是整段取代（#50）。
 * 原本照 Spring AI ChatMemoryRepository 的 saveAll：讀出整段 → 跑模型（好幾秒）→ 整段刪掉重插。
 * 同一段對話同時送兩句（兩個分頁、按了兩次送出），兩個請求讀到同一份歷史，晚存的那個整段蓋掉早的，
 * 早的那句和它的回覆就不見了，而且沒有任何錯誤（V7 當時寫明接受這個代價）。
 * 改成每一輪只把「這一輪新增的那幾則」接在最後面：兩句都留著，誰先誰後照存進來的順序。
 *
 * 為什麼不是樂觀鎖（version 欄位、衝突回 409）：對話不是「兩個人改同一份東西」，兩句都是使用者真的說過的，
 * 沒有誰該被退回；回 409 要使用者重送，重送就要重跑一次模型（又是好幾秒、又花一次錢），換來的結果跟直接接上去一樣。
 * 代價：晚到的那句，模型回它的時候沒看到同時送出的那一句（兩個請求本來就是並行的）；但兩句和兩個回覆都存著，下一輪模型都看得到。
 *
 * of(owner) 回傳 Spring AI 的 ChatMemory（add / get / clear），而不是自己定一個 port：
 * 對話記憶是 AI 助理（inbound adapter）跟框架之間的事，core 不知道有「對話」這回事。
 * 用 ChatMemory 而不是 ChatMemoryRepository，是因為框架裡「往後接」的語意就是 ChatMemory.add；
 * ChatMemoryRepository 只有 saveAll（整段取代），照它寫就一定是讀-改-寫，躲不掉上面那個遺失更新。
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
    private final int keepTurns;

    /**
     * @param keepTurns 一段對話最多留幾輪（一輪 = 使用者一句話，加上之後的工具呼叫和回覆）。
     *                  每次接上新的一輪，就把更早的輪刪掉：只往後接的話，一直在聊的對話不會被清理排程刪，會無限長下去
     */
    public JdbcAssistantMemoryRepository(JdbcClient jdbc, TransactionTemplate transaction, int keepTurns) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
        this.transaction = Objects.requireNonNull(transaction, "transaction 不可為 null");
        if (keepTurns < 1) {
            throw new IllegalArgumentException("keepTurns 至少要 1：" + keepTurns);
        }
        this.keepTurns = keepTurns;
    }

    /** 這個人名下的對話。物件很輕（只記著擁有者），每個請求拿一個新的就好。 */
    public ChatMemory of(OwnerId owner) {
        return new OwnedMemory(Objects.requireNonNull(owner, "owner 不可為 null").value());
    }

    /**
     * 刪掉 cutoff 之前就沒再說過話的對話，回傳刪了幾段。不分擁有者：這是這張表自己的維護工作，不是誰的請求。
     *
     * 「最後說話的時間」= 那段對話最新一列的 created_at：寫入是只往後接，最新一列就是最後那一輪存進來的時間。
     * 整段一起刪，不會留下只剩後半截、開頭被砍掉的對話。
     * 一段對話是 (owner_id, conversation_id) 一組：兩個人剛好用了同一個 conversation_id，也是各算各的。
     */
    public int deleteInactiveSince(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff 不可為 null");
        return jdbc.sql("""
                        WITH inactive AS (
                            SELECT owner_id, conversation_id FROM calendar_assistant_message
                            GROUP BY owner_id, conversation_id
                            HAVING max(created_at) < :cutoff
                        )
                        DELETE FROM calendar_assistant_message m
                        USING inactive
                        WHERE m.owner_id = inactive.owner_id AND m.conversation_id = inactive.conversation_id
                        RETURNING m.owner_id, m.conversation_id
                        """)
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .query((rs, rowNum) -> List.of(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .set()
                .size();
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
         * 接在這段對話的最後面，包在一個交易裡：不會留下「插了一半」的一輪。
         *
         * 接在哪裡（position）要先看目前最後一則是第幾則；兩個請求同時看，會拿到同一個號碼，第二個撞主鍵失敗。
         * 所以先拿這段對話的 advisory lock（交易結束自動放掉），同一段對話的寫入排隊，一次一個；
         * 鎖只包「查號碼 → 插入 → 刪舊輪」這幾句 SQL，跑模型的那幾秒不在裡面，不同的對話也不互相等。
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
                    lock(id);
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
                // 連不上資料庫時，失敗發生在開交易那一步，丟的是 TransactionException，不是 DataAccessException
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

        // 鎖的是 (擁有者, 對話) 這一組；hashtextextended 把它變成 advisory lock 要的 bigint。
        // 不同對話剛好撞到同一個雜湊值，只是多排一下隊，不會錯
        private void lock(UUID id) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "calendar_assistant_message:" + owner + ":" + id)
                    .query((rs, rowNum) -> 0)
                    .list();
        }

        /**
         * 只留最近 keepTurns 輪：從倒數第 keepTurns 句使用者的話開始留，更早的刪掉。
         * 以輪為單位刪，不從中間截：工具結果前面一定要有呼叫它的那則助理訊息，拆開了 API 會拒收整個請求。
         * position 不重新編號：讀的時候只看順序，號碼從幾開始都一樣。
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
