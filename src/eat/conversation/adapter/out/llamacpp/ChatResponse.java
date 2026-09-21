package eat.conversation.adapter.out.llamacpp;

import java.util.List;
import java.util.Map;

/**
 * 從 llama.cpp /v1/chat/completions 的回應裡取出模型講的話。
 *
 * 跟 {@link ChatRequest} 是一對：那邊把 domain 翻成協定，這邊把協定翻回文字。
 * 回傳 String 而不是 Reply，是為了讓這個 class 只管「讀懂協定」；
 * 組裝成 domain 型別是 Adapter 本體的事，那樣之後 Reply 長出新欄位時只有一個地方要改。
 */
final class ChatResponse {

    private ChatResponse() {
    }

    /**
     * 取出 choices[0].message.content。
     *
     * 刻意忽略 reasoning_content：Qwen3 的思考過程由 server 分離到那個欄位，
     * 它不屬於對話歷史 —— 把它記進 Conversation 只會污染下一輪的上下文。
     *
     * 也刻意忽略 finish_reason：被截斷（length）時內容仍然有用，照常回傳。
     * 等 Reply 長出「完成狀態」欄位時，這裡才有地方放它。
     */
    static String text(String json) {
        // 壞掉或被截斷的 JSON 會在這裡就炸，而且訊息帶位置
        Map<?, ?> response = asObject(JsonParser.parse(json), "回應");

        // server 出錯時回的是 error 物件而不是 choices。
        // 先認出這種情況，並把 server 自己的訊息原封不動帶出去 —— 那是排查問題最值錢的線索
        Object error = response.get("error");
        if (error != null) {
            throw new IllegalStateException("llama.cpp 回報錯誤：" + describeError(error));
        }

        if (!(response.get("choices") instanceof List<?> choices)) {
            throw new IllegalStateException("回應裡沒有 choices 陣列");
        }
        if (choices.isEmpty()) {
            throw new IllegalStateException("回應的 choices 是空的");
        }

        // 只看第一個：request 沒有要求 n > 1，所以永遠只會有一個
        Map<?, ?> choice = asObject(choices.get(0), "choices[0]");
        Map<?, ?> message = asObject(choice.get("message"), "choices[0].message");

        if (!(message.get("content") instanceof String text)) {
            throw new IllegalStateException("choices[0].message.content 不是字串");
        }
        // Reply 不收空白文字（規則 4）。在這裡就擋掉，錯誤訊息才會指向真正的原因
        // ——「模型沒有產生內容」，而不是讓 Reply 的建構子丟出一句看不出兇手是誰的訊息
        if (text.isBlank()) {
            throw new IllegalStateException("模型沒有產生任何內容");
        }
        return text;
    }

    private static Map<?, ?> asObject(Object value, String what) {
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalStateException(what + "不是 JSON 物件");
        }
        return object;
    }

    private static String describeError(Object error) {
        // 正常情況是 {"code":..,"message":"..","type":".."}，
        // 但格式不如預期時也不能再炸一次 —— 那會蓋掉原本的錯誤，讓人完全看不到發生什麼事
        if (error instanceof Map<?, ?> details && details.get("message") instanceof String message) {
            return message;
        }
        return String.valueOf(error);
    }
}
