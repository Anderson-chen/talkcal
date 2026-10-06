package eat.calendar.adapter.out.extraction.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.calendar.application.domain.model.EventDescription;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

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
    @DisplayName("Prompt")
    class PromptShape {

        private final Prompt prompt = ExtractionRequest.prompt(new EventDescription("明天三點跟小明吃飯"), MONDAY_MORNING);

        @Test
        @DisplayName("第一則是帶著現在時間與日期表的 system，第二則是使用者原話")
        void systemThenUser() {
            List<Message> messages = prompt.getInstructions();

            assertEquals(2, messages.size());
            assertEquals(MessageType.SYSTEM, messages.get(0).getMessageType());
            assertTrue(messages.get(0).getText().contains("現在是 2026-10-05 09:30"));
            assertTrue(messages.get(0).getText().contains("2026-10-14 = 下週三"));
            assertEquals(MessageType.USER, messages.get(1).getMessageType());
            assertEquals("明天三點跟小明吃飯", messages.get(1).getText());
        }

        @Test
        @DisplayName("帶上這個請求自己的選項：生成上限、溫度 0、json_schema 約束")
        void carriesItsOwnOptions() {
            OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();

            assertEquals(ExtractionRequest.MAX_TOKENS, options.getMaxTokens());
            // 溫度 0：抽行程要確定性，不用 server 替聊天調的溫度
            assertEquals(0.0, options.getTemperature());
            assertEquals(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA, options.getResponseFormat().getType());
            assertEquals(ExtractionRequest.SCHEMA, options.getResponseFormat().getJsonSchema());
        }
    }

    @Nested
    @DisplayName("JSON Schema")
    class Schema {

        // 讀得成 JSON 本身就是一個檢查：SCHEMA 是字串，寫壞了編譯器不會發現
        private final JsonNode schema = parse(ExtractionRequest.SCHEMA);

        @Test
        @DisplayName("欄位全部 required，end 可以是 null")
        void requiresEveryField() {
            JsonNode item = schema.at("/properties/events/items");
            assertEquals(List.of("title", "start", "end", "startSaid", "endSaid", "category", "location"),
                    item.get("required").valueStream().map(JsonNode::asString).toList());
            assertEquals("null", item.at("/properties/end/anyOf/1/type").asString());
            assertEquals("null", item.at("/properties/location/anyOf/1/type").asString());
        }

        @Test
        @DisplayName("分類用 enum 鎖死四個值：模型只能從裡面挑")
        void locksCategories() {
            assertEquals(List.of("work", "personal", "health", "social"),
                    schema.at("/properties/events/items/properties/category/enum")
                            .valueStream().map(JsonNode::asString).toList());
        }
    }

    private static JsonNode parse(String json) {
        return JSON.readTree(json);
    }
}
