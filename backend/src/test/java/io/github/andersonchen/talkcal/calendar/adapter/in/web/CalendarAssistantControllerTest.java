package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.andersonchen.talkcal.calendar.adapter.in.assistant.CalendarAssistant;
import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.service.InMemoryProposals;
import io.github.andersonchen.talkcal.calendar.application.domain.service.TrackProposalsService;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 這一層：conversationId 怎麼發、錯誤怎麼對應。助理背後接一個假的 ChatModel，不打真模型。
 */
@WebMvcTest(CalendarAssistantController.class)
@Import(CalendarAssistantControllerTest.StubConfiguration.class)
@DisplayName("CalendarAssistantController")
class CalendarAssistantControllerTest {

    private static final String URL = "/api/calendar/assistant";
    // 另一個登入的人（預設的請求是 ...0001）
    private static final String ME = "3f1c8a2e-0000-4000-8000-000000000001";
    private static final String OTHER = "3f1c8a2e-0000-4000-8000-000000000002";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;

    /**
     * 這裡測的是 controller 本身（路由、JSON、狀態碼），不是認證：每個請求預設都是「已經登入、帶著 CSRF token」。
     * 沒登入回 401、沒帶 CSRF 回 403 這些規則，在 SecurityTest 對真的 SecurityFilterChain 測。
     */
    @BeforeEach
    void signedIn() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user(ME)).with(csrf()))
                .build();
    }

    @Autowired
    AtomicReference<RuntimeException> failure;

    @Autowired
    RecordedUsage usage;

    @BeforeEach
    void reset() {
        failure.set(null);
        usage.clear();
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
    @DisplayName("#36 回覆裡有幾張卡片就記幾張「給人看了」，每張帶一個 proposalId；沒有卡片的回覆不記")
    void countsShownProposals() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"明天下午三點開會\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposals.length()").value(1))
                .andExpect(jsonPath("$.proposals[0].proposalId").value(Matchers.matchesPattern(UUID_PATTERN)));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"明天七點和 Amy 見面\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposals").isEmpty());

        assertEquals(List.of("shown 1"), usage.events);
    }

    @Test
    @DisplayName("#36 模型出事（502）時不記：使用者沒看到任何卡片")
    void failedReplyCountsNothing() throws Exception {
        failure.set(new IllegalStateException("模型掛了"));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"明天下午三點開會\"}"))
                .andExpect(status().isBadGateway());

        assertEquals(List.of(), usage.events);
    }

    // 跟助理說一句會提議的話，拿回那張卡片的 proposalId
    private String proposeCard(String signedIn) throws Exception {
        String body = mockMvc.perform(post(URL).with(user(signedIn)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"明天下午三點開會\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.proposals[0].proposalId");
    }

    @Test
    @DisplayName("#223 POST /dismissals 帶自己卡片的 id：算一次拒絕，回 204；拒絕不會被記成採納（沒有 added）")
    void recordsDismissals() throws Exception {
        String card = proposeCard(ME);
        usage.clear();

        mockMvc.perform(post(URL + "/dismissals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalIds\":[\"" + card + "\"]}"))
                .andExpect(status().isNoContent());

        assertEquals(List.of("dismissed 1"), usage.events);
    }

    @Test
    @DisplayName("#223 反例：同一張再拒絕一次、別人拿我的卡片 id、沒發過的 id：都回 204，但都不算")
    void dismissalsAreVerifiedByServer() throws Exception {
        String card = proposeCard(ME);
        mockMvc.perform(post(URL + "/dismissals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"proposalIds\":[\"" + card + "\"]}")).andExpect(status().isNoContent());
        String othersCard = proposeCard(OTHER);
        usage.clear();

        for (String body : List.of(
                "{\"proposalIds\":[\"" + card + "\"]}",
                "{\"proposalIds\":[\"00000000-0000-4000-8000-000000000000\"]}")) {
            mockMvc.perform(post(URL + "/dismissals").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent());
        }
        // 別人的卡片：我按不掉，回應跟成功一模一樣（不透露那張卡片存在）
        mockMvc.perform(post(URL + "/dismissals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposalIds\":[\"" + othersCard + "\"]}"))
                .andExpect(status().isNoContent());

        assertEquals(List.of(), usage.events);
    }

    @Test
    @DisplayName("#223 POST /dismissals 沒帶 proposalIds、id 不是 UUID：400，什麼都不記")
    void rejectsBadDismissals() throws Exception {
        for (String body : List.of("{}", "{\"proposalIds\":null}", "{\"proposalIds\":[\"abc\"]}", "{\"proposalIds\":[null]}")) {
            mockMvc.perform(post(URL + "/dismissals").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        assertEquals(List.of(), usage.events);
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
    @DisplayName("#104 別人拿到我的 conversationId：讀回來是空的，接著說話也只是在他自己名下開一段，我的那段不受影響")
    void conversationsBelongToSignedInUser() throws Exception {
        String id = "9d2f8c1a-3b4e-4f6a-8c7d-0e1f2a3b4c5d";
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"明天七點和 Amy 見面\",\"conversationId\":\"" + id + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL + "/" + id).with(user(OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isEmpty());
        mockMvc.perform(post(URL).with(user(OTHER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"嗨\",\"conversationId\":\"" + id + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get(URL + "/" + id))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].text").value("明天七點和 Amy 見面"));
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

        @Bean
        RecordedUsage usage() {
            return new RecordedUsage();
        }

        // 記用量走真的 service（核對卡片的規則在那裡），只把「卡片存哪」「記到哪」換成記憶體
        @Bean
        TrackProposalsService trackProposals(RecordedUsage usage) {
            InMemoryProposals proposals = new InMemoryProposals();
            return new TrackProposalsService(proposals, usage);
        }

        // 假模型：使用者講「開會」就提議（呼叫 propose_events，拿到結果後回一句話），其他都回「早上還是晚上？」；
        // failure 設了就丟那個例外
        @Bean
        CalendarAssistant calendarAssistant(AtomicReference<RuntimeException> failure) {
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    if (failure.get() != null) {
                        throw failure.get();
                    }
                    List<Message> messages = prompt.getInstructions();
                    Message last = messages.getLast();
                    if (last.getMessageType() == MessageType.TOOL) {
                        return new ChatResponse(List.of(new Generation(new AssistantMessage("請確認卡片。"))));
                    }
                    if (last.getText().contains("開會")) {
                        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "propose_events",
                                        "{\"description\":\"明天下午三點開會\"}")))
                                .build())));
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("早上還是晚上？"))));
                }
            };
            // 每個擁有者一份記憶，跟真的實作一樣分開
            Map<OwnerId, InMemoryChatMemoryRepository> memories = new ConcurrentHashMap<>();
            return new CalendarAssistant(model, owner -> memories.computeIfAbsent(owner, o -> new InMemoryChatMemoryRepository()),
                    description -> List.of(CalendarEvent.startingAt("開會", LocalDateTime.of(2026, 10, 7, 15, 0))),
                    (owner, start, end) -> List.of(), (owner, start, end, period, minimum) -> List.of(),
                    Clock.systemDefaultZone());
        }
    }

    /** 記下每一筆用量，寫成「shown 1」這種字串，比對時一眼看得懂。 */
    static final class RecordedUsage implements RecordUsagePort {

        final List<String> events = new ArrayList<>();

        @Override
        public void proposalsShown(int count) {
            events.add("shown " + count);
        }

        @Override
        public void proposalsDismissed(int count) {
            events.add("dismissed " + count);
        }

        @Override
        public void eventsAdded(EventOrigin origin, int count) {
            events.add("added " + origin + " " + count);
        }

        void clear() {
            events.clear();
        }
    }
}
