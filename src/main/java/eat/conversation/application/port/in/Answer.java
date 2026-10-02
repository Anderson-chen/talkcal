package eat.conversation.application.port.in;

import eat.conversation.application.domain.model.ConversationId;
import eat.conversation.application.domain.model.Reply;

import java.util.Objects;

/**
 * 問完一題拿回來的東西：模型的回覆，以及這段對話的 ID（下一題帶著它就是接續同一段對話）。
 */
public record Answer(ConversationId conversationId, Reply reply) {

    public Answer {
        Objects.requireNonNull(conversationId, "conversationId 不可為 null");
        Objects.requireNonNull(reply, "reply 不可為 null");
    }
}
