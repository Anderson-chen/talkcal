package eat.conversation.application.port.in;

import eat.conversation.application.domain.model.ConversationId;

import java.util.Optional;

/**
 * 提問並取得模型回覆（inbound port）：由 Adapter（網頁…）呼叫，由 domain/service 實作。
 *
 * conversationId 沒給：開一段新對話。有給：接續那一段，模型看得到之前的問答。
 * 不收 Conversation 物件：對話存在哪、怎麼讀出來是 use case 的事，呼叫端只需要知道 ID。
 *
 * 失敗時：
 * - 提問空白、ID 格式不對 → IllegalArgumentException
 * - 指定的對話不存在 → ConversationNotFoundException
 * - 同一段對話同時被問了兩題，這一題晚存 → ConversationChangedException
 * - 模型、檢索、資料庫出事 → IllegalStateException（outbound port 的約定）
 */
public interface AskQuestionUseCase {

    Answer askQuestion(Optional<ConversationId> conversationId, String question);
}
