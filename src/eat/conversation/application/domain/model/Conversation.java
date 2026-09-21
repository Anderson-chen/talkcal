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

    private final String instruction;
    private final List<Message> messages = new ArrayList<>();

    private Conversation(String instruction) {
        this.instruction = instruction;
    }

    public static Conversation start() {
        return new Conversation(null);
    }

    // 系統指令只能在開始時設定一次（規則 2）
    public static Conversation start(String instruction) {
        return new Conversation(requireText(instruction));
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

    // 文字不可為 null 或空白（規則 4）
    private static String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文字不可為 null 或空白");
        }
        return text;
    }
}
