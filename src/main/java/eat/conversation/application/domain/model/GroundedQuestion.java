package eat.conversation.application.domain.model;

import java.util.List;

/**
 * 一個帶著依據的提問（RAG 的 A：Augmented）。
 *
 * 它的職責只有一件事：把「檢索到的片段」和「使用者的問題」組成一段要送給模型的文字。
 * 這件事是業務規則不是技術細節 —— 「要求模型只依資料回答」正是 RAG 用來壓制幻覺的手段，
 * 把它放在 model 裡，llama.cpp / OpenAI / Claude 三個 adapter 就不必各寫一份，
 * 而且改 prompt 不會動到任何一個 adapter。
 *
 * 為什麼不直接寫進 Conversation：那個類別管的是對話的狀態機
 * （提問回覆交替、系統指令只設一次、歷史不可修改），
 * 跟「單次提問怎麼組」是兩個層次的關注點，混在一起 Conversation 會越長越胖。
 */
public record GroundedQuestion(Question question, List<Passage> passages) {

    // 防幻覺的那句話。放在參考資料「之後」、問題「之前」是有考量的：
    // 放最前面會被後面大量的資料淹掉；放在問題之後又離問題太遠。
    // 夾在中間，模型剛讀完資料就看到規矩，接著馬上看到問題。
    private static final String GROUNDING_INSTRUCTION =
            "請依據上述參考資料回答，資料中沒有提到的內容不要自行推測。";

    public GroundedQuestion {
        // 空白與否已經由 Question 保證（規則 4），這裡只剩「有沒有給」要檢查
        if (question == null) {
            throw new IllegalArgumentException("提問不可為 null");
        }
        if (passages == null) {
            throw new IllegalArgumentException("參考片段不可為 null；沒有檢索到東西請傳空清單");
        }
        // 複製一份，外部之後再改原本那個 List 也影響不到這裡（與 Conversation.messages() 同一個作法）。
        // List.copyOf 順帶擋掉夾帶 null 元素的清單。
        passages = List.copyOf(passages);
    }

    /**
     * 組成實際要送給模型的提問文字。
     */
    public String text() {
        // 沒檢索到任何東西時就是一般提問，不多說什麼。
        //
        // 另外兩個選項刻意沒選：
        // 一是告訴模型「沒有找到參考資料」—— 那等於暗示它「你可以自由發揮」，
        //   反而常釣出「我找不到相關資料」這種對使用者沒用的回覆；
        // 二是直接拒答、連模型都不呼叫 —— 太武斷，檢索漏掉不代表模型自己不知道。
        // 這是一條真正的業務規則，之後要改只改這一個 if。
        if (passages.isEmpty()) {
            return question.text();
        }

        StringBuilder text = new StringBuilder("參考資料：\n");
        for (int i = 0; i < passages.size(); i++) {
            Passage passage = passages.get(i);
            // 編號讓模型有辦法在回覆裡指名出處（「根據 [2]」），少了它只能含糊說「根據資料」。
            // 出處放在內容前面而不是後面，模型讀到內容時就已經知道這段是哪來的。
            text.append('[').append(i + 1).append(']')
                    .append('（').append(passage.source()).append('）')
                    .append(passage.text())
                    .append('\n');
        }
        text.append('\n').append(GROUNDING_INSTRUCTION).append("\n\n");
        // 「問題：」這個前綴是分隔線，免得模型把提問當成參考資料的最後一段
        text.append("問題：").append(question.text());
        return text.toString();
    }
}
