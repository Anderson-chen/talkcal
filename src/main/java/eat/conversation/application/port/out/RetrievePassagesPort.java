package eat.conversation.application.port.out;

import eat.conversation.application.domain.model.Passage;

import java.util.List;

/**
 * 檢索相關知識（outbound port）：由核心定義、由 Adapter（關鍵字比對、向量檢索、全文搜尋）實作。
 *
 * core 說得出口的只有一句話：「給我跟這個問題相關的片段」。
 * 怎麼找 —— 要不要算向量、幾維、餘弦相似度、文件切多大一段、取幾筆、相似度門檻設多少 ——
 * 一個字都不在這裡。那些全是 adapter 的內部決定，換一種檢索方式，這個介面不必改。
 *
 * 實作必須保證：
 * 1. 回傳的清單不為 null。沒有相關片段時回空清單 —— 那是正常結果，不是錯誤。
 * 2. 已依相關性由高到低排序。Passage 上刻意沒有 score，
 *    所以「誰比較相關」這件事，順序是 core 唯一能拿到的資訊。
 * 3. 已篩掉不夠相關的片段。只有 adapter 知道自己那套分數的尺度，core 沒有能力判斷。
 * 4. 檢索本身失敗（例如檢索服務連不上）時丟出非受檢例外，與 GenerateReplyPort 的約定一致。
 */
public interface RetrievePassagesPort {

    // 只收提問文字，不收 Conversation：adapter 既拿不到、也改不動對話狀態，
    // 這點與 GenerateReplyPort「只接收不可變的資料」是同一個考量。
    //
    // 多輪對話的指代（「那它要煎幾分鐘？」的「它」）確實需要歷史才能解析，
    // 但目前每個 HTTP 請求都開一段全新對話，歷史永遠只有一輪，傳進去也沒人用。
    // 等多輪對話真的出現，再回來改這個簽章 —— 到那時才知道它真正該長什麼形狀。
    List<Passage> retrievePassages(String question);
}
