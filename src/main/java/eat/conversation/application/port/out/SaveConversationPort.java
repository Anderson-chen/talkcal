package eat.conversation.application.port.out;

import eat.conversation.application.domain.model.Conversation;

/**
 * 把一段對話存起來（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 *
 * 一次存整段對話（aggregate），不是一則一則存訊息：對話和它的訊息要嘛一起寫進去、要嘛都沒寫，
 * 這個保證由實作負責（例如包在一個資料庫交易裡），core 不必知道有交易這回事。
 *
 * 實作必須保證：
 * 1. 以 conversation.version() 檢查衝突：從讀出來到現在，若有別的請求先存過同一段對話，
 *    丟 ConversationChangedException（在 domain.model），而且什麼都不寫 —— 不能讓後存的蓋掉先存的。
 * 2. 存檔本身失敗（資料庫連不上）時丟出其他非受檢例外，與其他 outbound port 的約定一致。
 */
public interface SaveConversationPort {

    void save(Conversation conversation);
}
