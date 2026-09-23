package eat.conversation.application.domain.model;

/**
 * 一段可用來回答問題的外部知識（檢索結果）。
 *
 * 它不知道自己是怎麼被找出來的 —— 關鍵字比對、向量相似度、全文檢索，core 一律不問，
 * 那是 outbound adapter 的事。所以這裡刻意沒有下面這幾個欄位：
 *
 * - score（相似度分數）：關鍵字比對算出的 0.6 和餘弦相似度的 0.6 是兩種量綱，
 *   core 拿到也無從判斷。而且 core 一旦寫出「低於 0.3 就丟掉」，
 *   就等於認識了某一種檢索實作的尺度，之後想換檢索方式就得回頭改核心。
 *   篩掉不夠相關的片段是 adapter 的責任 —— 它知道自己的尺度；
 *   core 收到的清單已經篩過、排好序，「誰比較相關」List 的順序就說完了。
 * - embedding（向量）：core 認識向量的那一刻，檢索方式就被焊死了。
 * - id：目前沒有「從片段反查原文件」的需求，等真的有了再加。
 */
public record Passage(String text, String source) {

    public Passage {
        // 文字不可為 null 或空白（規則 4）
        requireText(text, "片段文字");
        // 來源一樣必填：沒有出處就無法查證，而 RAG 一半的價值在於答案能被回溯。
        // 設成必填，adapter 就沒辦法偷懶不交代這段知識是哪來的。
        requireText(source, "片段來源");
    }

    private static void requireText(String text, String name) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + "不可為 null 或空白");
        }
    }
}
