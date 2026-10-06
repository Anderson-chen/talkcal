package eat.calendar.adapter.out.extraction.springai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.Category;
import eat.calendar.application.domain.model.EventDescription;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 從 ChatModel 的回應裡讀出行程，翻成 domain 的 CalendarEvent。
 *
 * 外層信封（/v1/chat/completions 的 choices[0].message.content）Spring AI 已經拆好了，變成 ChatResponse；
 * 這裡拆的是內層：content 這個「字串」裡面的 JSON —— 也就是 ExtractionRequest 用 schema 約束出來的那份。
 *
 * 模型給的時間要拿使用者原句核對：結束時間核對得到才採用、沒講上下午的 1～6 點當成下午（規則在 SaidTimes），其他欄位照收。
 *
 * 文法約束已經保證了形狀，這裡還是每個欄位都檢查一遍：
 * 約束是 server 那頭的事，換一台沒開文法的 server、換一版行為不同的 llama.cpp，
 * 這個 class 不該因此把壞資料安靜地放進來。
 *
 * 所有失敗一律丟 IllegalStateException，連「讀不懂的 JSON」也是。
 * 這裡讀的每一個 byte 都是模型產生的，壞了是上游的錯（502），不是使用者送錯（400）。
 */
final class ExtractionResponse {

    // 被截斷或被接在一起的 JSON 不能安靜地只讀前半段
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private ExtractionResponse() {
    }

    /**
     * description 是使用者的原句：模型說「使用者講了結束時間」時，要拿它回原句核對（見 endIsGrounded）。
     */
    static List<CalendarEvent> events(ChatResponse response, EventDescription description) {
        Objects.requireNonNull(response, "response 不可為 null");
        Objects.requireNonNull(description, "description 不可為 null");

        Generation generation = response.getResult();
        if (generation == null) {
            throw new IllegalStateException("回應裡沒有任何生成結果");
        }

        // 聊天的回覆被截斷了還能讀，JSON 被截斷就壞了 —— 所以 finish_reason = length 直接算失敗，
        // 錯誤訊息指向真正的原因，而不是一句「JSON 讀不懂」。
        // 不分大小寫：Spring AI 照抄 SDK 給的字，OpenAI 協定是小寫，但 SDK 的列舉印出來可能是大寫
        String finishReason = generation.getMetadata().getFinishReason();
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new IllegalStateException("模型輸出超過 " + ExtractionRequest.MAX_TOKENS + " tokens 被截斷");
        }

        String content = generation.getOutput().getText();
        if (content == null) {
            throw new IllegalStateException("模型沒有產生任何內容");
        }

        JsonNode events = parse(content, "模型輸出").get("events");
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
        LocalDateTime modelStart = dateTime(requiredString(node, "start", where), where + ".start");
        Category category = category(requiredString(node, "category", where), where);
        String location = optionalString(node, "location", where);
        JsonNode end = node.get("end");
        String startSaid = optionalString(node, "startSaid", where);
        String endSaid = optionalString(node, "endSaid", where);
        // 先檢查格式：end 不是字串也不是 null，就是模型壞了 —— 就算等一下不採用它，也不該假裝沒看到
        LocalDateTime endTime = null;
        if (end != null && !end.isNull()) {
            if (!end.isString()) {
                throw new IllegalStateException(where + ".end 不是字串也不是 null");
            }
            endTime = dateTime(end.asString(), where + ".end");
        }
        // 結束時間：原句裡核對得到才採用，否則當成沒講
        if (endTime != null && !SaidTimes.endIsGrounded(startSaid, endSaid, said)) {
            endTime = null;
        }
        // 沒講上下午的 1～6 點當成下午（使用者定的規則）。開始時間挪了，沒講上下午的結束時間也跟著挪
        LocalDateTime start = modelStart;
        if (SaidTimes.meansAfternoon(startSaid, start.getHour(), said)) {
            start = start.plusHours(12);
            if (endTime != null && SaidTimes.meansAfternoon(endSaid, endTime.getHour(), said)) {
                endTime = endTime.plusHours(12);
            }
        }
        try {
            // 沒有（採用的）結束時間就交給 domain 補預設長度，這裡只挑對建構方式
            CalendarEvent timed = endTime != null
                    ? new CalendarEvent(title, start, endTime)
                    : CalendarEvent.startingAt(title, start);
            // 備註不交給模型，永遠是沒有；使用者要在預覽畫面自己補
            return timed.withCategory(category).withDetails(location, null);
        } catch (IllegalArgumentException e) {
            // domain 的規則擋下了（例如結束早於開始）。使用者的描述沒錯，是模型解析錯了，所以翻成上游的錯
            throw new IllegalStateException("模型解析出的行程不合規則（" + where + "）：" + e.getMessage(), e);
        }
    }

    /**
     * 協定上的小寫字串 → domain 的 Category。對照寫成 switch：
     * 哪天 schema 多了一類而這裡沒跟上，會丟例外而不是安靜地塞進預設值。
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
}
