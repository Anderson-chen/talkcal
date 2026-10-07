package eat.calendar.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eat.calendar.adapter.in.assistant.CalendarAssistant;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 這一層：conversationId 怎麼發、錯誤怎麼對應。助理背後接一個假的 ChatModel，不打真模型。
 */
@WebMvcTest(CalendarAssistantController.class)
@Import(CalendarAssistantControllerTest.StubConfiguration.class)
@DisplayName("CalendarAssistantController")
class CalendarAssistantControllerTest {

    private static final String URL = "/api/calendar/assistant";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AtomicReference<RuntimeException> failure;

    @BeforeEach
    void reset() {
        failure.set(null);
    }

    @Test
    @DisplayName("不帶 conversationId：開一段新的（發一個 UUID），回助理說的話；沒有提議就是空陣列")
    void newConversation() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"明天七點和 Amy 見面\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(Matchers.matchesPattern(UUID_PATTERN)))
                .andExpect(jsonPath("$.reply").value("早上還是晚上？"))
                .andExpect(jsonPath("$.proposals").isEmpty());
    }

    @Test
    @DisplayName("帶著 conversationId：原樣接續")
    void continuesConversation() throws Exception {
        String id = "3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10";

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"晚上\",\"conversationId\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(id));
    }

    @Test
    @DisplayName("conversationId 不是 UUID、訊息空白：400")
    void badRequests() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"嗨\",\"conversationId\":\"abc\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("模型出事（就算它丟的是 IllegalArgumentException）：502，不是 400")
    void upstreamFailure() throws Exception {
        failure.set(new IllegalArgumentException("SDK 裡面的參數錯誤"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"嗨\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET：讀回這段對話給人看的部分（user / assistant），沒有這段就是空陣列")
    void history() throws Exception {
        String id = "4bcfcbd5-9f27-4343-bd37-f2d0839c97a2";
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"明天七點和 Amy 見面\",\"conversationId\":\"" + id + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(id))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].text").value("明天七點和 Amy 見面"))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].text").value("早上還是晚上？"));
        mockMvc.perform(get(URL + "/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty());
    }

    @Test
    @DisplayName("GET 的 conversationId 不是 UUID：400")
    void badIdOnHistory() throws Exception {
        mockMvc.perform(get(URL + "/abc")).andExpect(status().isBadRequest());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        AtomicReference<RuntimeException> failure() {
            return new AtomicReference<>();
        }

        // 假模型：每次都回「早上還是晚上？」；failure 設了就丟那個例外
        @Bean
        CalendarAssistant calendarAssistant(AtomicReference<RuntimeException> failure) {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    if (failure.get() != null) {
                        throw failure.get();
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("早上還是晚上？"))));
                }
            };
            return new CalendarAssistant(model, new InMemoryChatMemoryRepository(),
                    description -> List.of(), (start, end) -> List.of(), (start, end, period, minimum) -> List.of(),
                    Clock.systemDefaultZone());
        }
    }
}
