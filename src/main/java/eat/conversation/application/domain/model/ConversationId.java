package eat.conversation.application.domain.model;

import java.util.UUID;

/**
 * 一段對話的身分。
 *
 * 包成自己的型別而不是到處傳 UUID：方法簽章上看得出「這裡要的是對話的 ID」，
 * 之後多了別種 ID（使用者、訊息）也不會傳錯。
 *
 * 用 UUID 而不是資料庫的流水號：ID 在 domain 裡、還沒存檔之前就要有（Conversation.start() 當下），
 * 不必等資料庫發號；而且流水號會洩漏「總共有幾段對話」，外人也猜得到別人的對話編號。
 */
public record ConversationId(UUID value) {

    public ConversationId {
        if (value == null) {
            throw new IllegalArgumentException("對話 ID 不可為 null");
        }
    }

    public static ConversationId newId() {
        return new ConversationId(UUID.randomUUID());
    }

    /**
     * 從外部（例如 HTTP 請求）收到的字串。格式不對是呼叫端送錯，丟 IllegalArgumentException，
     * 跟其他「輸入不合規」同一種例外。
     */
    public static ConversationId of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("對話 ID 不可為 null 或空白");
        }
        try {
            return new ConversationId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("對話 ID 格式不對：" + text, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
