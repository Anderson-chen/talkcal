package io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

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
