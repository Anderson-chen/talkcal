package eat.calendar.adapter.out.extraction.springai;

import eat.calendar.adapter.shared.DateTable;
import eat.calendar.application.domain.model.EventDescription;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 把「一句自然語言 + 現在時間」翻譯成要交給 Spring AI ChatModel 的 Prompt。
 *
 * 跟聊天用的是同一個 ChatModel，但要的東西不一樣：
 * 聊天要一段自由文字，這邊要一份機器讀得懂的行程清單。差別全在這個 class 裡 ——
 * system prompt 怎麼寫、附上哪張日期表、用什麼 JSON Schema 約束輸出。
 *
 * 這些都是「怎麼讓這顆模型做對這件事」的知識，所以屬於 adapter，不屬於 domain：
 * 換成 Claude 或 OpenAI，日期表可能根本不需要，schema 的寫法也不一樣（tool use），
 * 但 CalendarEvent 的規則一條都不會變。
 */
final class ExtractionRequest {

    // 生成上限。文法約束只管形狀不管長度：模型要是一直往 events 陣列裡塞東西，
    // 會像壓測時那題 thinking 暴走一樣佔住一個 slot 直到逾時。
    // 兩個行程實測約 114 tokens，1024 夠一句話塞十幾個行程，再多就是模型失控了
    static final int MAX_TOKENS = 1024;

    // 這個請求自己的取樣溫度：0 = 每一步都挑機率最高的 token（貪婪解碼），同樣的輸入永遠同樣的輸出。
    // server 啟動時的 0.7 是替聊天調的，聊天要一點變化；抽行程只有一個正確答案，變化就是錯誤。
    // 實測：同一份 body 在 0.7 下 10 次有 1 次把「下週三」查成下週二，0 之後 7 種句子各 10 次全對
    static final double TEMPERATURE = 0;

    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 時間格式寫進 pattern：文法約束解碼會讓模型「只能」吐出這個形狀，
    // 而這個形狀剛好就是 LocalDateTime.parse 吃的 ISO 格式（不帶秒）
    private static final String DATE_TIME_PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$";

    /**
     * 輸出必須長這樣。end 可以是 null（沒講結束時間），由 ExtractionResponse 交給 domain 補預設長度。
     * 欄位全部 required、不准多欄位：形狀固定，剖析那頭才不必猜。
     * category 用 enum 鎖死四個值：模型「只能」從裡面挑，不會發明出「會議」「其他」這種第五類。
     * location 跟 end 一樣可以是 null（沒講地點）。備註不交給模型 —— 那是使用者自己補的東西。
     *
     * startSaid、endSaid 是模型「引用」的原話：使用者講開始、結束時間的那幾個字。
     * 模型給的 start / end 不一定可信（沒講結束時間會自己編、沒講上下午會照字面當凌晨），
     * ExtractionResponse 拿這些引用回原句核對、套規則。規則和原因寫在 SaidTimes。
     *
     * 透過 OpenAI 協定的 response_format（json_schema）送過去，llama.cpp 把它轉成文法（GBNF）在解碼時強制執行 ——
     * 不合 schema 的 token 根本選不到，比在 prompt 裡拜託模型「請回 JSON」可靠得多。
     * 但它保證不了語意（日期算錯、結束早於開始），那些還是要靠 domain 的規則擋。
     *
     * 是字串不是 JsonNode：Spring AI 收的就是字串。寫壞了（少一個括號）不會在這裡炸，
     * 會變成每次解析都失敗 —— ExtractionRequestTest 把它讀成 JSON 檢查，守著這件事。
     */
    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "events": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "title": { "type": "string", "minLength": 1 },
                      "start": { "type": "string", "pattern": "%1$s" },
                      "end":   { "anyOf": [ { "type": "string", "pattern": "%1$s" }, { "type": "null" } ] },
                      "startSaid": { "type": "string", "minLength": 1 },
                      "endSaid": { "anyOf": [ { "type": "string", "minLength": 1 }, { "type": "null" } ] },
                      "category": { "type": "string", "enum": ["work", "personal", "health", "social"] },
                      "location": { "anyOf": [ { "type": "string", "minLength": 1 }, { "type": "null" } ] }
                    },
                    "required": ["title", "start", "end", "startSaid", "endSaid", "category", "location"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["events"],
              "additionalProperties": false
            }
            """.formatted(DATE_TIME_PATTERN);

    private ExtractionRequest() {
    }

    static Prompt prompt(EventDescription description, LocalDateTime now) {
        Objects.requireNonNull(description, "description 不可為 null");
        Objects.requireNonNull(now, "now 不可為 null");

        return new Prompt(
                List.of(new SystemMessage(instruction(now)), new UserMessage(description.text())),
                options());
    }

    /**
     * 這個請求自己的選項，蓋過 application.properties 裡替聊天設的預設（spring.ai.openai.chat.options.*）。
     * 三個都是這件工作專屬的：max_tokens 是失控保險、response_format 是輸出約束、
     * temperature 是「這個請求要確定性」。聊天沒有理由跟 server 的取樣設定不同，這裡有，而且只屬於這種請求。
     *
     * 用的是 OpenAiChatOptions 而不是通用的 ChatOptions：通用的那份沒有 response_format。
     * 這也是這個 adapter 唯一綁在「OpenAI 協定」上的地方 —— 換成 Claude，要改的就是這個方法。
     */
    static OpenAiChatOptions options() {
        return OpenAiChatOptions.builder()
                .temperature(TEMPERATURE)
                .maxTokens(MAX_TOKENS)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(SCHEMA)
                        .build())
                .build();
    }

    /**
     * 給模型的指示。每一句都是對著實測踩到的坑寫的：
     * - 「從下表找，不要自己推算」：不 thinking 的 8B 模型心算星期幾不可靠，「下週三」給過星期一、星期二
     * - 「跨過午夜要用隔天的日期」：不講的話「晚上十點到凌晨一點」的結束時間會被丟掉
     * - 「沒有行程就回空陣列」：不講的話閒聊也可能被硬湊出一個行程
     * - title「有講跟誰就保留」：叫它別把地點寫進標題時，它連「跟小明」都一起刪了，只剩「吃飯」
     * - location 的例子：只寫「有講地點才填」時，「在3F會議室A」抽得到、「在健身房」卻抽不到
     * - category 每一類都舉例：只給類名時，模型對「看牙醫」該算 health 還是 personal 會猶豫
     */
    static String instruction(LocalDateTime now) {
        return """
                你把使用者的話整理成行事曆行程，一句話裡有幾個行程就列幾筆。
                現在是 %s。
                日期一律從下表找對應的那一列，不要自己推算：
                %s
                時間格式用 yyyy-MM-ddTHH:mm。
                startSaid：使用者原話裡講開始時間的那幾個字，連同前面的「早上」「下午」這類字一字不改照抄（例如「明天下午三點開會」→「下午三點」、「明天三點開會」→「三點」）。
                endSaid：使用者原話裡講結束時間的那幾個字，一字不改照抄（例如「九點到十點半看牙醫」→「十點半」）；原話沒講結束時間就給 null。
                有講結束時間就一定要填 end，跨過午夜的結束時間要用隔天的日期；沒講結束時間 end 給 null。
                title 寫要做的事，有講跟誰就保留（例如「跟小明吃飯」「跟客戶開會」），但不要包含日期、時間和地點。
                category 從四類選一個：work（工作、會議、客戶、報告、面試）、health（運動、看醫生、健身、瑜珈）、social（跟朋友或家人吃飯、聚會、約會）、personal（其他私事，例如繳費、讀書、購物）。
                location：使用者有講在哪裡就填那個地點（例如「在健身房上課」→ 健身房）；沒講就給 null，不要自己編。
                沒有任何行程就回空陣列。""".formatted(now.format(NOW_FORMAT), DateTable.of(now.toLocalDate()));
    }

}
