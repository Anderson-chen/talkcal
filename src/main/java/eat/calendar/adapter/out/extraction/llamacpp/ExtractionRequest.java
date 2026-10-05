package eat.calendar.adapter.out.extraction.llamacpp;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import eat.calendar.application.domain.model.EventDescription;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;

/**
 * 把「一句自然語言 + 現在時間」翻譯成 llama.cpp /v1/chat/completions 的 request body。
 *
 * 跟 conversation 的 ChatRequest 打的是同一個端點，但要的東西不一樣：
 * 那邊要一段自由文字，這邊要一份機器讀得懂的行程清單。差別全在這個 class 裡 ——
 * system prompt 怎麼寫、附上哪張日期表、用什麼 JSON Schema 約束輸出。
 *
 * 這些都是「怎麼讓這顆模型做對這件事」的知識，所以屬於 adapter，不屬於 domain：
 * 換成 Claude 或 OpenAI，日期表可能根本不需要，schema 的寫法也不一樣（tool use），
 * 但 CalendarEvent 的規則一條都不會變。
 */
final class ExtractionRequest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // 生成上限。文法約束只管形狀不管長度：模型要是一直往 events 陣列裡塞東西，
    // 會像壓測時那題 thinking 暴走一樣佔住一個 slot 直到逾時。
    // 兩個行程實測約 114 tokens，1024 夠一句話塞十幾個行程，再多就是模型失控了
    static final int MAX_TOKENS = 1024;

    // 這個請求自己的取樣溫度：0 = 每一步都挑機率最高的 token（貪婪解碼），同樣的輸入永遠同樣的輸出。
    // server 啟動時的 0.7 是替聊天調的，聊天要一點變化；抽行程只有一個正確答案，變化就是錯誤。
    // 實測：同一份 body 在 0.7 下 10 次有 1 次把「下週三」查成下週二，0 之後 7 種句子各 10 次全對
    static final double TEMPERATURE = 0;

    // 日期表涵蓋幾週（從這週一算起）。使用者最遠會說到「下下週」，再遠的通常會講日期
    private static final int WEEKS = 3;
    private static final List<String> WEEK_LABELS = List.of("這週", "下週", "下下週");
    private static final List<String> DAY_LABELS = List.of("今天", "明天", "後天");
    private static final String WEEKDAYS = "一二三四五六日";

    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // 時間格式寫進 pattern：文法約束解碼會讓模型「只能」吐出這個形狀，
    // 而這個形狀剛好就是 LocalDateTime.parse 吃的 ISO 格式（不帶秒）
    private static final String DATE_TIME_PATTERN = "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$";

    /**
     * 輸出必須長這樣。end 可以是 null（沒講結束時間），由 ExtractionResponse 交給 domain 補預設長度。
     * 三個欄位都 required、不准多欄位：形狀固定，剖析那頭才不必猜。
     *
     * llama.cpp 把它轉成文法（GBNF）在解碼時強制執行 —— 不合 schema 的 token 根本選不到，
     * 比在 prompt 裡拜託模型「請回 JSON」可靠得多。
     * 但它保證不了語意（日期算錯、結束早於開始），那些還是要靠 domain 的規則擋。
     */
    private static final JsonNode SCHEMA = parse("""
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
                      "end":   { "anyOf": [ { "type": "string", "pattern": "%1$s" }, { "type": "null" } ] }
                    },
                    "required": ["title", "start", "end"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["events"],
              "additionalProperties": false
            }
            """.formatted(DATE_TIME_PATTERN));

    private ExtractionRequest() {
    }

    static String body(EventDescription description, LocalDateTime now) {
        Objects.requireNonNull(description, "description 不可為 null");
        Objects.requireNonNull(now, "now 不可為 null");

        List<Message> messages = List.of(
                new Message("system", instruction(now)),
                new Message("user", description.text()));
        return write(new Body(messages, false, MAX_TOKENS, TEMPERATURE,
                new ResponseFormat("json_schema", new JsonSchema("calendar_events", SCHEMA))));
    }

    /**
     * 給模型的指示。每一句都是對著實測踩到的坑寫的：
     * - 「從下表找，不要自己推算」：不 thinking 的 8B 模型心算星期幾不可靠，「下週三」給過星期一、星期二
     * - 「跨過午夜要用隔天的日期」：不講的話「晚上十點到凌晨一點」的結束時間會被丟掉
     * - 「沒有行程就回空陣列」：不講的話閒聊也可能被硬湊出一個行程
     */
    static String instruction(LocalDateTime now) {
        return """
                你把使用者的話整理成行事曆行程，一句話裡有幾個行程就列幾筆。
                現在是 %s。
                日期一律從下表找對應的那一列，不要自己推算：
                %s
                時間格式用 yyyy-MM-ddTHH:mm。
                有講結束時間就一定要填 end，跨過午夜的結束時間要用隔天的日期；沒講結束時間 end 給 null。
                title 只寫要做的事，不要包含日期和時間。
                沒有任何行程就回空陣列。""".formatted(now.format(NOW_FORMAT), dateTable(now.toLocalDate()));
    }

    /**
     * 從這週一起連續 WEEKS 週，每天一列：「2026-10-14 = 下週三」，今天 / 明天 / 後天另外標出來。
     *
     * 這張表把「推算日期」換成「比對字串」：模型只要在表裡找到「下週三」那一列，
     * 不必知道今天星期幾、也不必做加法。日曆的算術交給 java.time，那是它的專長。
     *
     * 一週從星期一開始（台灣的習慣）：星期日說「下週一」指的是明天，表裡也剛好是這樣排。
     */
    static String dateTable(LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        StringBuilder table = new StringBuilder();
        for (int i = 0; i < WEEKS * 7; i++) {
            LocalDate date = monday.plusDays(i);
            table.append(date)
                    .append(" = ").append(WEEK_LABELS.get(i / 7)).append(WEEKDAYS.charAt(date.getDayOfWeek().getValue() - 1));
            long daysFromToday = date.toEpochDay() - today.toEpochDay();
            if (daysFromToday >= 0 && daysFromToday < DAY_LABELS.size()) {
                table.append(" = ").append(DAY_LABELS.get((int) daysFromToday));
            }
            table.append('\n');
        }
        // 最後一個換行拿掉，讓呼叫端的版面自己決定
        return table.toString().stripTrailing();
    }

    private static String write(Body body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JacksonException e) {
            // 序列化的是自己組好的資料，寫不出來是程式接錯線，當成 bug 往外丟
            throw new IllegalStateException("組 request body 失敗", e);
        }
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JacksonException e) {
            // 上面那份 schema 是寫死的常數，讀不懂就是打錯字，類別載入時就該炸
            throw new IllegalStateException("內建的 JSON Schema 寫壞了", e);
        }
    }

    /**
     * 真正送出去的 JSON。跟 ChatRequest.Body 一樣刻意不送 model（單模型常駐，送了也被忽略）。
     * 多出來的三個欄位都是這件工作專屬的：max_tokens 是失控保險、response_format 是輸出約束、
     * temperature 是「這個請求要確定性」—— ChatRequest 不覆蓋取樣參數，是因為聊天沒有理由跟 server 的設定不同；
     * 這裡有理由，而且是只屬於這種請求的理由，所以寫在這裡而不是去改 server 的啟動參數。
     * 協定用 snake_case，Java 用 camelCase，用 @JsonProperty 對上 —— 只在 adapter 裡，core 看不到。
     */
    record Body(List<Message> messages,
                boolean stream,
                @JsonProperty("max_tokens") int maxTokens,
                double temperature,
                @JsonProperty("response_format") ResponseFormat responseFormat) {
    }

    record Message(String role, String content) {
    }

    record ResponseFormat(String type, @JsonProperty("json_schema") JsonSchema jsonSchema) {
    }

    record JsonSchema(String name, JsonNode schema) {
    }
}
