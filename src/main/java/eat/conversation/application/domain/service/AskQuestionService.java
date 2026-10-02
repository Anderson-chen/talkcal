package eat.conversation.application.domain.service;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.ConversationId;
import eat.conversation.application.domain.model.GroundedQuestion;
import eat.conversation.application.domain.model.Passage;
import eat.conversation.application.domain.model.Question;
import eat.conversation.application.domain.model.Reply;
import eat.conversation.application.port.in.Answer;
import eat.conversation.application.port.in.AskQuestionUseCase;
import eat.conversation.application.port.in.ConversationNotFoundException;
import eat.conversation.application.port.out.GenerateReplyPort;
import eat.conversation.application.port.out.LoadConversationPort;
import eat.conversation.application.port.out.RetrievePassagesPort;
import eat.conversation.application.port.out.SaveConversationPort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 實作「提問並取得模型回覆」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則；規則一律寫在 model。
 *
 * 一題的流程：讀出（或開始）對話 → 檢索 → 生成 → 存檔。
 * 只在拿到回覆之後存一次：存檔時機刻意排在 LLM 之後，所以等 LLM 的那十幾秒不佔資料庫連線，
 * 也不會留下一個「問了但沒回」的半成品。代價是同一段對話被同時問兩題時，
 * 兩題都會跑完 LLM，晚存的那題才被版本號擋下（ConversationChangedException）。
 */
public final class AskQuestionService implements AskQuestionUseCase {

    private final LoadConversationPort loadConversationPort;
    private final RetrievePassagesPort retrievePassagesPort;
    private final GenerateReplyPort generateReplyPort;
    private final SaveConversationPort saveConversationPort;

    // 參數順序照執行順序排：讀出、檢索、生成、存檔
    public AskQuestionService(LoadConversationPort loadConversationPort,
                              RetrievePassagesPort retrievePassagesPort,
                              GenerateReplyPort generateReplyPort,
                              SaveConversationPort saveConversationPort) {
        this.loadConversationPort = Objects.requireNonNull(loadConversationPort, "loadConversationPort 不可為 null");
        this.retrievePassagesPort = Objects.requireNonNull(retrievePassagesPort, "retrievePassagesPort 不可為 null");
        this.generateReplyPort = Objects.requireNonNull(generateReplyPort, "generateReplyPort 不可為 null");
        this.saveConversationPort = Objects.requireNonNull(saveConversationPort, "saveConversationPort 不可為 null");
    }

    @Override
    public Answer askQuestion(Optional<ConversationId> conversationId, String question) {
        // 先交給 model 檢查，不合規的提問連資料庫、檢索都走不到，更不會呼叫模型。
        // 這一步一定要在最前面：後面每一步背後都是另一台 server，空白提問送過去，
        // 換來的只會是一個跟規則無關的錯誤（server 沒開時甚至是 502 而不是 400）。
        Question asked = new Question(question);

        Conversation conversation = conversationId
                .map(id -> loadConversationPort.load(id).orElseThrow(() -> new ConversationNotFoundException(id)))
                .orElseGet(Conversation::start);

        // 再檢索。這一步失敗時對話還沒被動過，沒有東西要撤回 ——
        // 例外直接往外丟就是正確行為，所以它刻意不在下面的 try 裡面。
        List<Passage> passages = retrievePassagesPort.retrievePassages(asked.text());

        // 記進歷史的是使用者原本問的那句話；帶著參考資料的版本只在這一次送給模型。
        // GenerateReplyPort 那一側照樣不必知道有 RAG：它收到的就是一串訊息。
        conversation.ask(asked.text());
        String grounded = new GroundedQuestion(asked, passages).text();
        Reply reply;
        try {
            reply = generateReplyPort.generateReply(
                    conversation.instruction(), conversation.messagesWithPendingQuestionAs(grounded));
            conversation.recordReply(reply.text());
        } catch (RuntimeException e) {
            // 沒拿到可用的回覆：撤回提問讓對話回到提問前。什麼都沒存，使用者再問一次就是重試
            conversation.withdrawQuestion();
            throw e;
        }

        saveConversationPort.save(conversation);
        return new Answer(conversation.id(), reply);
    }
}
