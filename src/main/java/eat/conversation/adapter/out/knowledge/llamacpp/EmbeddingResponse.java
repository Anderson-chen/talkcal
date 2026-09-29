package eat.conversation.adapter.out.knowledge.llamacpp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * 從 llama.cpp /v1/embeddings 的回應裡取出向量。
 *
 * 跟 {@link EmbeddingRequest} 是一對。回傳 float[] 而不是包成什麼型別，
 * 是為了讓這個 class 只管「讀懂協定」—— 怎麼用那串數字是 adapter 本體的事。
 *
 * 剖析交給 Jackson，但「讀到的東西代表什麼」「什麼情況算失敗」全留在這裡。
 */
final class EmbeddingResponse {

    // 跟 ChatResponse 用同一組設定：FAIL_ON_TRAILING_TOKENS 讓「一個完整的值後面還有東西」
    // 直接失敗。少了它，被截斷的回應會安靜地只讀前半段，把故障偽裝成正常結果
    // Jackson 3 的 mapper 建好就不可變，設定只能在 builder 上做（2.x 那種 new 完再 enable 已經不行）。
    // 這個功能在 Jackson 3 其實已經預設開啟，仍然明寫：這裡依賴的是它的效果，不想依賴某一版的預設值。
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private EmbeddingResponse() {
    }

    /**
     * 取出 data[0].embedding。
     */
    static float[] vector(String json) {
        JsonNode response = parse(json);
        if (!response.isObject()) {
            throw new IllegalStateException("回應不是 JSON 物件");
        }

        // server 出錯時回的是 error 物件而不是 data。
        // 把 server 自己的訊息原封帶出去 —— 那是排查問題最值錢的線索
        JsonNode error = response.get("error");
        if (error != null) {
            throw new IllegalStateException("llama.cpp 回報錯誤：" + describeError(error));
        }

        JsonNode data = response.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("回應裡沒有 data 陣列");
        }
        if (data.isEmpty()) {
            throw new IllegalStateException("回應的 data 是空的");
        }

        // 只看第一筆：request 一次只送一段文字，所以永遠只會有一筆
        JsonNode embedding = data.get(0).get("embedding");
        if (embedding == null || !embedding.isArray()) {
            throw new IllegalStateException("data[0].embedding 不是陣列");
        }
        if (embedding.isEmpty()) {
            throw new IllegalStateException("data[0].embedding 是空陣列");
        }
        // llama.cpp 原生的 /embedding 端點回的是「陣列的陣列」（每個 token 一列）。
        // 認出這種形狀並直說，比讓下面的迴圈丟出一句看不懂的錯誤有用得多 ——
        // 幾乎都是 baseUri 或路徑接錯，人會想不到要往那邊查
        if (embedding.get(0).isArray()) {
            throw new IllegalStateException(
                    "data[0].embedding 是巢狀陣列，可能打到了原生的 /embedding 而不是 /v1/embeddings");
        }

        float[] vector = new float[embedding.size()];
        for (int i = 0; i < vector.length; i++) {
            JsonNode value = embedding.get(i);
            if (!value.isNumber()) {
                throw new IllegalStateException("data[0].embedding[" + i + "] 不是數字");
            }
            // 收成 float 而不是 double：模型本來就是用 float32 算的，
            // 存成 double 只會讓記憶體變兩倍，精度一位都不會多
            vector[i] = value.floatValue();
        }
        return vector;
    }

    private static JsonNode parse(String json) {
        Objects.requireNonNull(json, "json 不可為 null");
        try {
            return JSON.readTree(json);
        } catch (JacksonException e) {
            // 讀不懂的 JSON 算「給進來的東西本身不對」，跟下面那些「讀得懂但內容不能用」
            // 的 IllegalStateException 分開，呼叫端光看型別就能分辨
            throw new IllegalArgumentException("回應不是合法的 JSON", e);
        }
    }

    private static String describeError(JsonNode error) {
        // 格式不如預期時也不能再炸一次 —— 那會蓋掉原本的錯誤
        JsonNode message = error.get("message");
        if (message != null && message.isString()) {
            return message.asString();
        }
        return error.isString() ? error.asString() : error.toString();
    }
}
