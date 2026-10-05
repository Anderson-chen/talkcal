package eat.conversation.adapter.out.reply.llamacpp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * 從 llama.cpp /v1/chat/completions 的回應裡取出模型講的話。
 *
 * 跟 {@link ChatRequest} 是一對：那邊把 domain 翻成協定，這邊把協定翻回文字。
 * 回傳 String 而不是 Reply，是為了讓這個 class 只管「讀懂協定」；
 * 組裝成 domain 型別是 Adapter 本體的事，那樣之後 Reply 長出新欄位時只有一個地方要改。
 *
 * 剖析交給 Jackson，但「讀到的東西代表什麼」全部留在這裡 ——
 * 哪個欄位才是回覆、哪個欄位要視而不見、什麼情況算失敗。
 * Jackson 只會把字串變成樹，它不會知道 thinking 不該進對話歷史。
 */
final class ChatResponse {

    // 讀進來的是外界給的資料，所以這個 mapper 比預設嚴格一點：
    // FAIL_ON_TRAILING_TOKENS 讓「一個完整的值後面還有東西」直接失敗。
    // 少了它，被截斷或被接在一起的回應會安靜地只讀前半段，把故障偽裝成正常結果
    // Jackson 3 的 mapper 建好就不可變，設定只能在 builder 上做（2.x 那種 new 完再 enable 已經不行）。
    // 這個功能在 Jackson 3 其實已經預設開啟，仍然明寫：這裡依賴的是它的效果，不想依賴某一版的預設值。
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

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
        JsonNode response = parse(json);
        if (!response.isObject()) {
            throw new IllegalStateException("回應不是 JSON 物件");
        }

        // server 出錯時回的是 error 物件而不是 choices。
        // 先認出這種情況，並把 server 自己的訊息原封不動帶出去 —— 那是排查問題最值錢的線索
        JsonNode error = response.get("error");
        if (error != null) {
            throw new IllegalStateException("llama.cpp 回報錯誤：" + describeError(error));
        }

        JsonNode choices = response.get("choices");
        if (choices == null || !choices.isArray()) {
            throw new IllegalStateException("回應裡沒有 choices 陣列");
        }
        if (choices.isEmpty()) {
            throw new IllegalStateException("回應的 choices 是空的");
        }

        // 只看第一個：request 沒有要求 n > 1，所以永遠只會有一個
        JsonNode message = choices.get(0).get("message");
        if (message == null || !message.isObject()) {
            throw new IllegalStateException("choices[0].message 不是 JSON 物件");
        }

        JsonNode content = message.get("content");
        // isString 同時擋掉「沒有這個欄位」以外的兩種壞情況：值是數字、值是 null
        if (content == null || !content.isString()) {
            throw new IllegalStateException("choices[0].message.content 不是字串");
        }

        String text = content.asString();
        // Reply 不收空白文字（規則 4）。在這裡就擋掉，錯誤訊息才會指向真正的原因
        // ——「模型沒有產生內容」，而不是讓 Reply 的建構子丟出一句看不出兇手是誰的訊息
        if (text.isBlank()) {
            throw new IllegalStateException("模型沒有產生任何內容");
        }
        return text;
    }

    private static JsonNode parse(String json) {
        Objects.requireNonNull(json, "json 不可為 null");
        try {
            return JSON.readTree(json);
        } catch (JacksonException e) {
            // 讀不懂的 JSON 也丟 IllegalStateException，跟下面「讀得懂但內容不能用」同一種。
            // 原本這裡丟 IllegalArgumentException，想讓呼叫端分得出「格式壞了」和「內容不對」，
            // 但例外型別在這個專案裡另有意義：ChatController 把 IllegalArgumentException 翻成 400（呼叫端送錯）。
            // 這份 JSON 是 llama.cpp 給的，壞了是上游的錯，該是 502。要分辨格式還是內容，看訊息和 cause 就夠了
            throw new IllegalStateException("回應不是合法的 JSON", e);
        }
    }

    private static String describeError(JsonNode error) {
        // 正常情況是 {"code":..,"message":"..","type":".."}，
        // 但格式不如預期時也不能再炸一次 —— 那會蓋掉原本的錯誤，讓人完全看不到發生什麼事
        JsonNode message = error.get("message");
        if (message != null && message.isString()) {
            return message.asString();
        }
        // 退而求其次：原樣印出來，至少還看得到 server 講了什麼
        return error.isString() ? error.asString() : error.toString();
    }
}
