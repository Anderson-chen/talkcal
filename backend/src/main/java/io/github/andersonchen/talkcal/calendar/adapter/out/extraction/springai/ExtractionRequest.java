package io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai;

import io.github.andersonchen.talkcal.calendar.adapter.shared.DateTable;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 跟 AI 助理用同一個 ChatModel，要的東西不一樣：助理要一段自由文字，這裡要一份程式能解析的行程清單。
 * 差別都在這個類別：system prompt、日期表、約束輸出的 JSON Schema。
 *
 * 這些是讓這個模型做對這件事的寫法，所以屬於 adapter：換成 Claude 或 OpenAI，日期表可能不需要，
 * schema 的寫法也不一樣（tool use），但 CalendarEvent 的規則一條都不會變。
 */
final class ExtractionRequest {

    // 文法約束只管形狀不管長度：模型一直往 events 陣列裡加東西的話，會占住 llama-server 的一個 slot 直到逾時。
    // 兩個行程實測約 114 tokens，1024 夠一句話放十幾個行程，再多就是模型失控了
    static final int MAX_TOKENS = 1024;

    // 0 是每一步都選機率最高的 token，同樣的輸入永遠同樣的輸出。抽行程只有一個正確答案，變化就是錯誤；
    // server 啟動時的 0.7（deploy/compose.yaml）是給要一點變化的自由文字。
    // 實測：同一份 body 在 0.7 下 10 次有 1 次把「下週三」查成下週二，0 之後 7 種句子各 10 次全對
    static final double TEMPERATURE = 0;

    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 文法約束解碼會讓模型只能輸出這個形狀，這個形狀剛好是 LocalDateTime.parse 能解析的 ISO 格式（不帶秒）
    private static final String DATE_TIME_PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$";

    /**
     * end 可以是 null（沒講結束時間），由 ExtractionResponse 交給 domain 補預設長度；location 也可以是 null。
     * 欄位全部 required、不准多欄位：形狀固定，解析那一端才不必猜。
     * category 用 enum 限定四個值：模型只能從裡面選，不會自創「會議」「其他」這種第五類。
     * 備註不交給模型：那是使用者自己補的。
     *
     * startSaid、endSaid 是模型照抄的原話：使用者講開始、結束時間的那幾個字。模型給的 start、end 不一定可信
     * （沒講結束時間會自己編、沒講上下午會照字面當凌晨），ExtractionResponse 拿引用回原句核對，規則見 SaidTimes。
     *
     * 透過 OpenAI 協定的 response_format（json_schema）送出。llama.cpp 把 schema 轉成 GBNF 文法，在解碼時強制執行：
     * 不合 schema 的 token 根本選不到，比在 prompt 裡要求「請回 JSON」可靠。但文法保證不了語意（日期算錯、結束早於開始），
     * 那些還是靠 domain 的規則擋。
     *
     * 是字串，不是 JsonNode：Spring AI 收的是字串。寫錯了（少一個括號）不會在這裡丟例外，而是每次解析都失敗，
     * ExtractionRequestTest 會把 SCHEMA 讀成 JSON 檢查。
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

    static Prompt prompt(EventDescription description, LocalDateTime now, ChatOptions defaults) {
        Objects.requireNonNull(description, "description 不可為 null");
        Objects.requireNonNull(now, "now 不可為 null");

        return new Prompt(
                List.of(new SystemMessage(instruction(now)), new UserMessage(description.text())),
                options(defaults));
    }

    /**
     * 從 application.properties 的預設（傳進來的 defaults）開始，再覆蓋這件工作專屬的三個：
     * max_tokens 防止失控、response_format 約束輸出、temperature 要結果確定。
     *
     * 一定要從預設開始：Spring AI 2.0 的 OpenAiChatModel 看到請求自帶選項就整份照用，不會補上預設，
     * 沒給模型名稱就送 gpt-5-mini。本機的 llama-server 不看名稱，Bedrock 會回 404。
     *
     * 用 OpenAiChatOptions：通用的 ChatOptions 沒有 response_format。這是這個 adapter 唯一綁定 OpenAI 協定的地方，
     * 換成 Claude 時要改的就是這個方法。
     */
    static OpenAiChatOptions options(ChatOptions defaults) {
        // 測試用的假 ChatModel 給的是通用的 ChatOptions，沒有 OpenAI 的欄位可以沿用
        OpenAiChatOptions.Builder start = defaults instanceof OpenAiChatOptions openAi
                ? openAi.mutate()
                : OpenAiChatOptions.builder();
        return start
                .temperature(TEMPERATURE)
                .maxTokens(MAX_TOKENS)
                .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                        .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(SCHEMA)
                        .build())
                .build();
    }

    /**
     * 每一句擋的都是模型真的會犯的錯：
     * - 「從下表找，不要自己推算」：關閉 thinking 的 8B 模型心算星期幾不可靠，「下週三」給過星期一、星期二
     * - 「跨過午夜要用隔天的日期」：不講的話「晚上十點到凌晨一點」的結束時間會被丟掉
     * - 「沒有行程就回空陣列」：不講的話，閒聊也可能被硬湊出一個行程
     * - title「有講跟誰就保留」：只要求模型別把地點寫進標題的話，「跟小明」也會一起刪掉，只剩「吃飯」
     * - location 的例子：只寫「有講地點才填」的話，「在3F會議室A」抽得到、「在健身房」卻抽不到
     * - category 每一類都舉例：只給類名的話，模型對「看牙醫」該算 health 還是 personal 會不穩定
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
