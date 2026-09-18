package application.port.out;

import application.domain.model.Conversation;
import application.domain.model.Reply;

import java.util.List;
import java.util.Optional;

/**
 * 產生回覆（outbound port）：由核心定義、由 Adapter（llama.cpp、OpenAI、Claude）實作。
 * 只接收不可變的資料，Adapter 無法改動對話狀態。
 */
public interface GenerateReplyPort {

    // messages 是完整歷史，最後一則是待回覆的提問；呼叫失敗時丟出非受檢例外
    Reply generateReply(Optional<String> instruction, List<Conversation.Message> messages);
}
