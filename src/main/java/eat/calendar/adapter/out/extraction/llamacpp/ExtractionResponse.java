package eat.calendar.adapter.out.extraction.llamacpp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import eat.calendar.application.domain.model.CalendarEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 從 llama.cpp 的回應裡讀出行程，翻成 domain 的 CalendarEvent。
 *
 * 要拆兩層信封：外層是 /v1/chat/completions 的回應（choices[0].message.content），
 * 內層是 content 這個「字串」裡面的 JSON —— 也就是 ExtractionRequest 用 schema 約束出來的那份。
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

    static List<CalendarEvent> events(String responseBody) {
        Objects.requireNonNull(responseBody, "responseBody 不可為 null");
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
            result.add(event(events.get(i), i));
        }
        return result;
    }

    private static CalendarEvent event(JsonNode node, int index) {
        String where = "events[" + index + "]";
        String title = requiredString(node, "title", where);
        LocalDateTime start = dateTime(requiredString(node, "start", where), where + ".start");
        JsonNode end = node.get("end");
        try {
            // 沒講結束時間時協定上是 null；預設多長交給 domain 決定，這裡只挑對建構方式
            if (end == null || end.isNull()) {
                return CalendarEvent.startingAt(title, start);
            }
            if (!end.isString()) {
                throw new IllegalStateException(where + ".end 不是字串也不是 null");
            }
            return new CalendarEvent(title, start, dateTime(end.asString(), where + ".end"));
        } catch (IllegalArgumentException e) {
            // domain 的規則擋下了（例如結束早於開始）。使用者的描述沒錯，是模型解析錯了，所以翻成上游的錯
            throw new IllegalStateException("模型解析出的行程不合規則（" + where + "）：" + e.getMessage(), e);
        }
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
