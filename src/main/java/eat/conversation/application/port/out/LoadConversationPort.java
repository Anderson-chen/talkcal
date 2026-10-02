package eat.conversation.application.port.out;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.ConversationId;

import java.util.Optional;

/**
 * 讀出一段存過的對話（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 *
 * 實作必須保證：
 * 1. 找不到回 Optional.empty()，不是例外 —— 「沒有這段對話」要不要算錯、算哪種錯，是 use case 的決定。
 * 2. 回來的 Conversation 透過 Conversation.restore 建，帶著存檔時的版本號，存回去時才檢查得了衝突。
 * 3. 讀取本身失敗（資料庫連不上）時丟出非受檢例外，與其他 outbound port 的約定一致。
 */
public interface LoadConversationPort {

    Optional<Conversation> load(ConversationId id);
}
