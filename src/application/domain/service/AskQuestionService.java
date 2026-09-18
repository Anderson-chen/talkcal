package application.domain.service;

import application.domain.model.Conversation;
import application.domain.model.Reply;
import application.port.in.AskQuestionUseCase;
import application.port.out.GenerateReplyPort;

import java.util.Objects;

/**
 * 實作「提問並取得模型回覆」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則；規則一律寫在 model。
 */
public final class AskQuestionService implements AskQuestionUseCase {

    private final GenerateReplyPort generateReplyPort;

    public AskQuestionService(GenerateReplyPort generateReplyPort) {
        this.generateReplyPort = Objects.requireNonNull(generateReplyPort, "generateReplyPort 不可為 null");
    }

    @Override
    public Reply askQuestion(Conversation conversation, String question) {
        // 先交給 model 檢查，不合規的提問不會呼叫模型
        conversation.ask(question);
        try {
            Reply reply = generateReplyPort.generateReply(conversation.instruction(), conversation.messages());
            conversation.recordReply(reply.text());
            return reply;
        } catch (RuntimeException e) {
            // 沒拿到可用的回覆：撤回提問讓對話回到提問前，使用者再問一次就是重試
            conversation.withdrawQuestion();
            throw e;
        }
    }
}
