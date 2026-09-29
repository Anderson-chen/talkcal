package eat.conversation.adapter.out.knowledge.llamacpp;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 組出 llama.cpp /v1/embeddings 的 request body。
 *
 * 跟 chat 那邊的 ChatRequest 是同一個角色，但工作輕得多 ——
 * 這裡沒有 domain 型別要翻譯，只有一段純文字要送出去。
 * 即使如此還是獨立成一個 class：送出什麼欄位是個決定，那些決定應該有個看得見的家。
 */
final class EmbeddingRequest {

    // ObjectMapper 設定好之後就是執行緒安全的，當常數重用；它不便宜，不該每次呼叫都 new
    private static final ObjectMapper JSON = new ObjectMapper();

    private EmbeddingRequest() {
    }

    /**
     * 刻意不帶 model 欄位，理由與 ChatRequest 相同：server 是單模型常駐，這個欄位會被忽略。
     * （注意這跟 OpenAI 的正規 API 不同，那邊 model 是必填 ——
     * 之後真要接 OpenAI，那是另一個 adapter 的事。）
     */
    static String body(String text) {
        // 空白文字算不出有意義的向量，而且有些 server 直接回 400。
        // 在這裡先炸，錯誤訊息才指得出真正的原因
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("要轉成向量的文字不可為 null 或空白");
        }
        return write(new Body(text, "float"));
    }

    private static String write(Body body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JacksonException e) {
            // 序列化的是我們自己組好的資料，寫不出來代表程式接錯線，不是外界的問題
            throw new IllegalStateException("組 request body 失敗", e);
        }
    }

    /**
     * 協定上的 request 形狀。只有兩個欄位，一眼看得完。
     *
     * encoding_format 明寫成 "float"：server 的預設值本來就是它，
     * 但另一個選項 "base64" 回來的是編碼過的字串，解析方式完全不同。
     * 寫出來，讓「這個 adapter 收的是浮點數陣列」變成 body 上看得見的事實，而不是依賴預設。
     *
     * 元件用 Java 的命名習慣，再用 @JsonProperty 指定線路上的名字 ——
     * 讓 Java 這一側讀起來正常，同時不騙協定。
     */
    record Body(String input, @JsonProperty("encoding_format") String encodingFormat) {
    }
}
