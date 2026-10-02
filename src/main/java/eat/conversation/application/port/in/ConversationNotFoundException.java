package eat.conversation.application.port.in;

import eat.conversation.application.domain.model.ConversationId;

/**
 * 呼叫端指定要接續的對話不存在。
 *
 * 不默默開一段新對話：呼叫端以為自己在接續上一段，模型卻什麼歷史都沒有，回答會莫名其妙。
 * 明確告訴它「沒有這段對話」，要開新的就別帶 ID。
 */
public final class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(ConversationId id) {
        super("找不到對話 " + id + "；要開始新的對話請不要帶 conversationId");
    }
}
