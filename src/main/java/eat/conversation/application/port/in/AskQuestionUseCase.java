package eat.conversation.application.port.in;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.Reply;

/**
 * 提問並取得模型回覆（inbound port）：由 Adapter（CLI、網頁）呼叫，由 domain/service 實作。
 */
public interface AskQuestionUseCase {

    Reply askQuestion(Conversation conversation, String question);
}
