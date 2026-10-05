package eat.calendar.adapter.out.extraction.llamacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.calendar.application.domain.model.EventDescription;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ExtractionRequest")
class ExtractionRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // 2026-10-05 是星期一
    private static final LocalDateTime MONDAY_MORNING = LocalDateTime.of(2026, 10, 5, 9, 30);

    @Nested
    @DisplayName("日期對照表")
    class DateTable {

        @Test
        @DisplayName("從這週一起連續三週，每天一列")
        void coversThreeWeeksFromMonday() {
            List<String> rows = ExtractionRequest.dateTable(LocalDate.of(2026, 10, 7)).lines().toList();

            assertEquals(21, rows.size());
            assertTrue(rows.getFirst().startsWith("2026-10-05 = 這週一"));
            assertTrue(rows.getLast().startsWith("2026-10-25 = 下下週日"));
        }

        @Test
        @DisplayName("「下週三」那一列真的是星期三 —— 實測時模型自己算錯的就是這個")
        void nextWednesdayIsAWednesday() {
            String table = ExtractionRequest.dateTable(MONDAY_MORNING.toLocalDate());

            assertTrue(table.contains("2026-10-14 = 下週三"));
        }

        @Test
        @DisplayName("標出今天、明天、後天")
        void marksTodayTomorrowAndTheDayAfter() {
            String table = ExtractionRequest.dateTable(LocalDate.of(2026, 10, 7));

            assertTrue(table.contains("2026-10-07 = 這週三 = 今天"));
            assertTrue(table.contains("2026-10-08 = 這週四 = 明天"));
            assertTrue(table.contains("2026-10-09 = 這週五 = 後天"));
            // 今天以前的日子不標
            assertTrue(table.contains("2026-10-06 = 這週二\n"));
        }

        @Test
        @DisplayName("今天是星期日：一週從星期一開始，所以「明天」是下週一")
        void sundayBelongsToTheWeekThatStartedOnMonday() {
            String table = ExtractionRequest.dateTable(LocalDate.of(2026, 10, 11));

            assertTrue(table.contains("2026-10-11 = 這週日 = 今天"));
            assertTrue(table.contains("2026-10-12 = 下週一 = 明天"));
        }
    }

    @Nested
    @DisplayName("request body")
    class Body {

        private final JsonNode body = parse(ExtractionRequest.body(new EventDescription("明天三點跟小明吃飯"), MONDAY_MORNING));

        @Test
        @DisplayName("第一則是帶著現在時間與日期表的 system，第二則是使用者原話")
        void systemThenUser() {
            JsonNode messages = body.get("messages");

            assertEquals(2, messages.size());
            assertEquals("system", messages.get(0).get("role").asString());
            assertTrue(messages.get(0).get("content").asString().contains("現在是 2026-10-05 09:30"));
            assertTrue(messages.get(0).get("content").asString().contains("2026-10-14 = 下週三"));
            assertEquals("user", messages.get(1).get("role").asString());
            assertEquals("明天三點跟小明吃飯", messages.get(1).get("content").asString());
        }

        @Test
        @DisplayName("用 json_schema 約束輸出，end 可以是 null")
        void constrainsOutputWithJsonSchema() {
            JsonNode format = body.get("response_format");

            assertEquals("json_schema", format.get("type").asString());
            JsonNode item = format.at("/json_schema/schema/properties/events/items");
            assertEquals(List.of("title", "start", "end"),
                    item.get("required").valueStream().map(JsonNode::asString).toList());
            assertEquals("null", item.at("/properties/end/anyOf/1/type").asString());
        }

        @Test
        @DisplayName("帶上生成上限，不串流")
        void capsGenerationAndDoesNotStream() {
            assertEquals(ExtractionRequest.MAX_TOKENS, body.get("max_tokens").asInt());
            assertFalse(body.get("stream").asBoolean());
        }

        @Test
        @DisplayName("溫度 0：抽行程要確定性，不用 server 替聊天調的溫度")
        void usesGreedyDecoding() {
            assertEquals(0.0, body.get("temperature").asDouble());
        }

        @Test
        @DisplayName("刻意不送 model")
        void leavesModelToTheServer() {
            assertFalse(body.has("model"));
        }
    }

    private static JsonNode parse(String json) {
        return JSON.readTree(json);
    }
}
