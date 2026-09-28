package eat.conversation.application.domain.service;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.GroundedQuestion;
import eat.conversation.application.domain.model.Passage;
import eat.conversation.application.domain.model.Question;
import eat.conversation.application.domain.model.Reply;
import eat.conversation.application.port.in.AskQuestionUseCase;
import eat.conversation.application.port.out.GenerateReplyPort;
import eat.conversation.application.port.out.RetrievePassagesPort;

import java.util.List;
import java.util.Objects;

/**
 * 實作「提問並取得模型回覆」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則；規則一律寫在 model。
 */
public final class AskQuestionService implements AskQuestionUseCase {

    private final RetrievePassagesPort retrievePassagesPort;
    private final GenerateReplyPort generateReplyPort;

    // 參數順序照執行順序排：先檢索、再生成
    public AskQuestionService(RetrievePassagesPort retrievePassagesPort, GenerateReplyPort generateReplyPort) {
        this.retrievePassagesPort = Objects.requireNonNull(retrievePassagesPort, "retrievePassagesPort 不可為 null");
        this.generateReplyPort = Objects.requireNonNull(generateReplyPort, "generateReplyPort 不可為 null");
    }

    @Override
    public Reply askQuestion(Conversation conversation, String question) {
        // 先交給 model 檢查，不合規的提問連檢索都走不到，更不會呼叫模型。
        // 這一步一定要在檢索之前：檢索背後是另一台 server，空白提問送過去，
        // 換來的只會是一個跟規則無關的錯誤（server 沒開時甚至是 502 而不是 400）。
        Question asked = new Question(question);

        // 再檢索。這一步失敗時對話還沒被動過，沒有東西要撤回 ——
        // 例外直接往外丟就是正確行為，所以它刻意不在下面的 try 裡面。
        List<Passage> passages = retrievePassagesPort.retrievePassages(asked.text());

        // 記進歷史的是「帶著參考資料的提問」，所以 GenerateReplyPort 那一側完全不必知道有 RAG。
        conversation.ask(new GroundedQuestion(asked, passages).text());
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
