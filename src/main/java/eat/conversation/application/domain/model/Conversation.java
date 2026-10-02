package eat.conversation.application.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 與語言模型的一段對話（Clean Architecture 最內圈的 Entity）。
 * 不依賴任何供應商、HTTP、JSON 或 SDK；呼叫模型與流程編排屬於 Use Case 層。
 */
public final class Conversation {

    public enum Role { USER, ASSISTANT }

    public record Message(Role role, String text) {
        public Message {
            if (role == null) {
                throw new IllegalArgumentException("role 不可為 null");
            }
            requireText(text);
        }
    }

    private final ConversationId id;
    private final String instruction;
    private final List<Message> messages = new ArrayList<>();
    // 樂觀鎖的版本號：這段對話被存過幾次。domain 不解讀它、也不改它，只負責帶著它往返 ——
    // 存檔的 adapter 拿它確認「從讀出來到現在，沒有別的請求先存過」。
    // 放在 aggregate 上而不是藏在 adapter 裡：讀和存是兩個不同的 adapter 呼叫，
    // 中間隔著 LLM 那十幾秒，「當初讀到的是哪一版」只能跟著這個物件走。
    private final long version;

    private Conversation(ConversationId id, String instruction, long version) {
        this.id = id;
        this.instruction = instruction;
        this.version = version;
    }

    public static Conversation start() {
        return new Conversation(ConversationId.newId(), null, 0);
    }

    // 系統指令只能在開始時設定一次（規則 2）
    public static Conversation start(String instruction) {
        return new Conversation(ConversationId.newId(), requireText(instruction), 0);
    }

    /**
     * 把存起來的對話還原回來（給讀取的 adapter 用）。
     *
     * 不直接把清單塞進欄位，而是一則一則重新走 ask / recordReply：
     * 資料庫裡的資料一樣要遵守規則 1、4，被手動改壞的紀錄在這裡就會被擋下，不會帶著壞狀態進到 use case。
     */
    public static Conversation restore(ConversationId id, String instruction, List<Message> messages, long version) {
        if (id == null) {
            throw new IllegalArgumentException("對話 ID 不可為 null");
        }
        if (messages == null) {
            throw new IllegalArgumentException("訊息清單不可為 null；沒有訊息請傳空清單");
        }
        if (version < 0) {
            throw new IllegalArgumentException("版本號不可為負數：" + version);
        }
        Conversation conversation =
                new Conversation(id, instruction == null ? null : requireText(instruction), version);
        for (Message message : messages) {
            switch (message.role()) {
                case USER -> conversation.ask(message.text());
                case ASSISTANT -> conversation.recordReply(message.text());
            }
        }
        return conversation;
    }

    public ConversationId id() {
        return id;
    }

    public long version() {
        return version;
    }

    public Optional<String> instruction() {
        return Optional.ofNullable(instruction);
    }

    // 提問與回覆必須交替（規則 1）
    public void ask(String text) {
        if (awaitingReply()) {
            throw new IllegalStateException("上一個提問還在等待回覆，不能再提問");
        }
        messages.add(new Message(Role.USER, text));
    }

    public void recordReply(String text) {
        if (!awaitingReply()) {
            throw new IllegalStateException("沒有待回覆的提問，不能記錄回覆");
        }
        messages.add(new Message(Role.ASSISTANT, text));
    }

    // 只能撤回還沒得到回覆的提問；已回覆的輪次不可修改（規則 3）
    public void withdrawQuestion() {
        if (!awaitingReply()) {
            throw new IllegalStateException("沒有待回覆的提問，不能撤回");
        }
        messages.removeLast();
    }

    public boolean awaitingReply() {
        return !messages.isEmpty() && messages.getLast().role() == Role.USER;
    }

    // 對外只給不可變的快照，外部無法修改歷史（規則 3）
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    /**
     * 這一次要送給模型的歷史：跟 messages() 一樣，只是最後那個待回覆的提問換成 text。
     *
     * 歷史記的是使用者真正問的那句話；檢索到的參考資料只屬於「這一次」呼叫，不進歷史。
     * 不這樣做的話，每一輪的參考資料都會留在歷史裡、下一輪又整包送出去 ——
     * context 越來越肥，還會塞進跟新問題無關的舊片段；存起來的對話紀錄也不是使用者說過的話。
     * 歷史本身不會被改到（規則 3）：回傳的是另一份清單。
     */
    public List<Message> messagesWithPendingQuestionAs(String text) {
        if (!awaitingReply()) {
            throw new IllegalStateException("沒有待回覆的提問，無從替換");
        }
        List<Message> sent = new ArrayList<>(messages);
        sent.set(sent.size() - 1, new Message(Role.USER, text));
        return List.copyOf(sent);
    }

    // 文字不可為 null 或空白（規則 4）
    private static String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文字不可為 null 或空白");
        }
        return text;
    }
}
