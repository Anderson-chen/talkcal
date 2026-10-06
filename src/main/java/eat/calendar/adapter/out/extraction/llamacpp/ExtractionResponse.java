package eat.calendar.adapter.out.extraction.llamacpp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.Category;
import eat.calendar.application.domain.model.EventDescription;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 從 llama.cpp 的回應裡讀出行程，翻成 domain 的 CalendarEvent。
 *
 * 要拆兩層信封：外層是 /v1/chat/completions 的回應（choices[0].message.content），
 * 內層是 content 這個「字串」裡面的 JSON —— 也就是 ExtractionRequest 用 schema 約束出來的那份。
 *
 * 模型給的結束時間要拿使用者原句核對過才採用（endIsGrounded），其他欄位照收。
 *
 * 文法約束已經保證了形狀，這裡還是每個欄位都檢查一遍：
 * 約束是 server 那頭的事，換一台沒開文法的 server、換一版行為不同的 llama.cpp，
 * 這個 class 不該因此把壞資料安靜地放進來。
 *
 * 所有失敗一律丟 IllegalStateException，連「讀不懂的 JSON」也是。
 * 這裡讀的每一個 byte 都是模型產生的，壞了是上游的錯（502），不是使用者送錯（400）——
 * （conversation 的 ChatResponse 原本在這點上丟 IllegalArgumentException，後來也改成一樣了。）
 */
final class ExtractionResponse {

    // 跟 ChatResponse 同一個理由：被截斷或被接在一起的 JSON 不能安靜地只讀前半段
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ExtractionResponse() {
    }

    /**
     * description 是使用者的原句：模型說「使用者講了結束時間」時，要拿它回原句核對（見 endIsGrounded）。
     */
    static List<CalendarEvent> events(String responseBody, EventDescription description) {
        Objects.requireNonNull(responseBody, "responseBody 不可為 null");
        Objects.requireNonNull(description, "description 不可為 null");
        JsonNode response = parse(responseBody, "回應");

        JsonNode error = response.get("error");
        if (error != null) {
            throw new IllegalStateException("llama.cpp 回報錯誤：" + describeError(error));
        }

        JsonNode choices = response.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("回應裡沒有 choices");
        }
        JsonNode choice = choices.get(0);

        // 聊天的回覆被截斷了還能讀，JSON 被截斷就壞了 —— 所以這裡跟 ChatResponse 相反，
        // finish_reason = length 直接算失敗，錯誤訊息指向真正的原因，而不是一句「JSON 讀不懂」
        JsonNode finishReason = choice.get("finish_reason");
        if (finishReason != null && "length".equals(finishReason.asString())) {
            throw new IllegalStateException("模型輸出超過 " + ExtractionRequest.MAX_TOKENS + " tokens 被截斷");
        }

        JsonNode content = choice.path("message").get("content");
        if (content == null || !content.isString()) {
            throw new IllegalStateException("choices[0].message.content 不是字串");
        }

        JsonNode events = parse(content.asString(), "模型輸出").get("events");
        if (events == null || !events.isArray()) {
            throw new IllegalStateException("模型輸出裡沒有 events 陣列");
        }

        List<CalendarEvent> result = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            result.add(event(events.get(i), i, description.text()));
        }
        return result;
    }

    private static CalendarEvent event(JsonNode node, int index, String said) {
        String where = "events[" + index + "]";
        String title = requiredString(node, "title", where);
        LocalDateTime start = dateTime(requiredString(node, "start", where), where + ".start");
        Category category = category(requiredString(node, "category", where), where);
        String location = optionalString(node, "location", where);
        JsonNode end = node.get("end");
        String endSaid = optionalString(node, "endSaid", where);
        // 先檢查格式：end 不是字串也不是 null，就是模型壞了 —— 就算等一下不採用它，也不該假裝沒看到
        LocalDateTime endTime = null;
        if (end != null && !end.isNull()) {
            if (!end.isString()) {
                throw new IllegalStateException(where + ".end 不是字串也不是 null");
            }
            endTime = dateTime(end.asString(), where + ".end");
        }
        try {
            // 沒講結束時間（或模型說有、但原句裡核對不到）就交給 domain 補預設長度，這裡只挑對建構方式
            CalendarEvent timed = endTime != null && endIsGrounded(endSaid, said)
                    ? new CalendarEvent(title, start, endTime)
                    : CalendarEvent.startingAt(title, start);
            // 備註不交給模型，永遠是沒有；使用者要在預覽畫面自己補
            return timed.withCategory(category).withDetails(location, null);
        } catch (IllegalArgumentException e) {
            // domain 的規則擋下了（例如結束早於開始）。使用者的描述沒錯，是模型解析錯了，所以翻成上游的錯
            throw new IllegalStateException("模型解析出的行程不合規則（" + where + "）：" + e.getMessage(), e);
        }
    }

    // 中文、全形、半形裡表示「到」的寫法：九點到十點半、兩點至三點、14:00-16:00、3～5 點
    private static final String RANGE_MARK = "(?:到|至|~|～|-|–|—)";
    private static final Pattern LEADING_RANGE_MARK = Pattern.compile("^" + RANGE_MARK + "\\s*");

    /**
     * 模型說使用者講了結束時間，而且引用了原話（endSaid）—— 這段引用在原句裡，是不是緊接在「到／至／-」後面？
     *
     * 為什麼不直接相信模型給的 end：實測「明天下午3點和 Amy 開會」這種沒講結束時間的句子，
     * 模型（溫度 0、每次都一樣）會給跟開始一樣的 15:00、或自己編一個 18:00，而不是 prompt 要求的 null。
     * 前者被 domain 擋下變成 502，後者更糟：安靜地存進一個使用者沒講過的時間。
     *
     * 只要求「引用的字在原句裡」也不夠：沒講結束時間時，模型會把開始時間那幾個字（「下午3點」）抄進 endSaid。
     * 真正的結束時間在中文裡幾乎都跟在「到」後面，開始時間前面是「明天」「早上」—— 所以多檢查這一個字。
     * 模型可以編時間，但編不出原句裡不存在的字。
     *
     * 代價：用時長講結束（「開會兩小時」）、或「開會到五點」以外的說法，會退回預設的一小時。寧可少採用，不要存錯。
     */
    static boolean endIsGrounded(String endSaid, String said) {
        if (endSaid == null || endSaid.isBlank()) {
            return false;
        }
        // 模型有時候連「到」一起抄（「到十點半」），先拿掉再核對
        String quoted = LEADING_RANGE_MARK.matcher(endSaid.strip()).replaceFirst("");
        if (quoted.isEmpty()) {
            return false;
        }
        return Pattern.compile(RANGE_MARK + "\\s*" + Pattern.quote(quoted)).matcher(said).find();
    }

    /**
     * 協定上的小寫字串 → domain 的 Category。跟 ChatRequest 的 protocolRole 同一招：
     * 對照寫成 switch，哪天 schema 多了一類而這裡沒跟上，會丟例外而不是安靜地塞進預設值。
     */
    private static Category category(String text, String where) {
        return switch (text) {
            case "work" -> Category.WORK;
            case "personal" -> Category.PERSONAL;
            case "health" -> Category.HEALTH;
            case "social" -> Category.SOCIAL;
            default -> throw new IllegalStateException(where + ".category 不是認得的分類：" + text);
        };
    }

    // 可以沒有的字串欄位：沒有這個欄位、或值是 null，都當成沒有；是別的型別就是模型壞了
    private static String optionalString(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new IllegalStateException(where + "." + field + " 不是字串也不是 null");
        }
        return value.asString();
    }

    private static String requiredString(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw new IllegalStateException(where + "." + field + " 不是字串");
        }
        return value.asString();
    }

    private static LocalDateTime dateTime(String text, String where) {
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(where + " 不是合法的時間：" + text, e);
        }
    }

    private static JsonNode parse(String json, String what) {
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isObject()) {
                throw new IllegalStateException(what + "不是 JSON 物件");
            }
            return node;
        } catch (JacksonException e) {
            throw new IllegalStateException(what + "不是合法的 JSON", e);
        }
    }

    private static String describeError(JsonNode error) {
        JsonNode message = error.get("message");
        if (message != null && message.isString()) {
            return message.asString();
        }
        return error.isString() ? error.asString() : error.toString();
    }
}
