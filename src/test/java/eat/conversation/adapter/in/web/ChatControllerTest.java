package eat.conversation.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import tools.jackson.databind.ObjectMapper;

/**
 * ChatController 的測試，真的走一遍 Spring MVC。
 *
 * 為什麼不寫成純單元測試（new 一個 controller 直接呼叫方法）：
 * 這個類別幾乎每一行都是在跟 Spring 講話 —— 路由掛在哪、@RequestBody 怎麼綁、
 * 回應怎麼序列化、@ExceptionHandler 有沒有被接上。直接呼叫方法那些全部驗不到。
 *
 * @WebMvcTest 只載入 web 那一層：ConversationConfiguration、Spring AI 的自動組裝都不會跑，
 * 這些測試不需要任何外部服務。ChatClient 由下面的 TestConfiguration 補上：
 * 真的 ChatClient + 記憶體裡的 ChatMemory，只有最底下的 ChatModel 是手寫的假物件 ——
 * starter-test 有帶 Mockito，但專案一律手寫假物件，這裡不破例。
 * 用真的 ChatClient 而不是假一個，是因為 conversationId 有沒有真的交到記憶那一層，只有真的跑一次才驗得到。
 */
@WebMvcTest(ChatController.class)
// 明確 Import 而不是靠巢狀 TestConfiguration 被自動撿走：
// 那套自動偵測在 @WebMvcTest 這種切片測試上不吃，容器裡會少掉 ChatClient
@Import(ChatControllerTest.StubConfiguration.class)
@DisplayName("ChatController")
class ChatControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String CHAT = "/api/chat";

    private static final String CONVERSATION = "3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StubChatModel chatModel;

    @BeforeEach
    void resetStub() {
        // 容器（連同這個假物件）在同一個測試類別裡是共用的，每題自己重置才不會互相影響
        chatModel.reset();
    }

    @Nested
    @DisplayName("正常路徑")
    class Success {

        @Test
        @DisplayName("把 JSON 裡的問題交給模型，再把回覆包成 JSON")
        void returnsReplyAsJson() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"中午吃什麼\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reply").value("牛肉麵"));

            assertEquals("中午吃什麼", chatModel.prompts.getFirst().getUserMessage().getText());
        }

        @Test
        @DisplayName("沒帶 conversationId：回應裡發一個新的 UUID")
        void noConversationIdGetsANewOne() throws Exception {
            String body = mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"中午吃什麼\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String conversationId = JSON.readTree(body).get("conversationId").asString();
            assertEquals(conversationId, UUID.fromString(conversationId).toString());
        }

        @Test
        @DisplayName("帶著同一個 conversationId 問第二題：模型看得到第一題的問答")
        void sameConversationIdCarriesHistory() throws Exception {
            String json = "{\"question\":\"%s\",\"conversationId\":\"" + CONVERSATION + "\"}";
            mockMvc.perform(post(CHAT).contentType(MediaType.APPLICATION_JSON).content(json.formatted("我的房號是 7777")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value(CONVERSATION));
            mockMvc.perform(post(CHAT).contentType(MediaType.APPLICATION_JSON).content(json.formatted("我的房號是多少")))
                    .andExpect(status().isOk());

            // 第二次送給模型的：第一題、第一題的回覆、第二題
            List<String> sent = chatModel.prompts.getLast().getInstructions().stream().map(Message::getText).toList();
            assertEquals(List.of("我的房號是 7777", "牛肉麵", "我的房號是多少"), sent);
        }
    }

    @Nested
    @DisplayName("錯誤要翻成正確的 HTTP 語言")
    class Failures {

        @Test
        @DisplayName("空白提問是呼叫端送錯東西：400，而且不驚動模型")
        void blankQuestionIsBadRequest() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"  \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("提問不可為 null 或空白"));

            assertTrue(chatModel.prompts.isEmpty());
        }

        @Test
        @DisplayName("沒有 question 欄位也算送錯東西：400")
        void missingQuestionIsBadRequest() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("conversationId 不是 UUID：400，而且不驚動模型")
        void malformedConversationIdIsBadRequest() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"熱量多少\",\"conversationId\":\"不是-uuid\"}"))
                    .andExpect(status().isBadRequest());

            assertTrue(chatModel.prompts.isEmpty(), "ID 都讀不懂就不該呼叫模型");
        }

        @Test
        @DisplayName("上游模型出事不是呼叫端的錯：502 而不是籠統的 500")
        void modelFailureIsBadGateway() throws Exception {
            chatModel.behaviour = prompt -> {
                throw new IllegalStateException("連不上模型");
            };

            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"中午吃什麼\"}"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error").value("連不上模型"));
        }

        /**
         * ChatClient 背後的任何一台 server 都可能丟 IllegalArgumentException（例如 SDK 檢查參數）。
         * 那不是呼叫端送錯，不能被 400 的 handler 撿走 —— ChatController 把 ChatClient 丟出的一切都包成上游失敗。
         */
        @Test
        @DisplayName("上游丟的 IllegalArgumentException 也是 502，不是 400")
        void upstreamIllegalArgumentIsStillBadGateway() throws Exception {
            chatModel.behaviour = prompt -> {
                throw new IllegalArgumentException("SDK 覺得參數不對");
            };

            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"中午吃什麼\"}"))
                    .andExpect(status().isBadGateway());
        }

        @Test
        @DisplayName("模型回了空內容：502")
        void emptyReplyIsBadGateway() throws Exception {
            chatModel.behaviour = prompt -> "";

            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"中午吃什麼\"}"))
                    .andExpect(status().isBadGateway());
        }

        /**
         * 回歸測試：一開始 502 的 handler 攔的是整個 RuntimeException，結果連 Spring 自己因為
         * 「JSON 格式不對」丟的 HttpMessageNotReadableException 都被吃進來、誤標成 502 ——
         * 那明明是呼叫端送錯，該 400。
         */
        @Test
        @DisplayName("JSON 本身壞掉：400 而不是 502，而且根本不該驚動模型")
        void malformedJsonIsBadRequestNotBadGateway() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\": 這不是合法的 JSON"))
                    .andExpect(status().isBadRequest());

            assertTrue(chatModel.prompts.isEmpty(), "請求都還沒讀懂就不該呼叫模型");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        StubChatModel chatModel() {
            return new StubChatModel();
        }

        // 跟 ConversationConfiguration 一樣掛上記憶，只是存在記憶體裡；RAG 不掛，那不是 controller 的事
        @Bean
        ChatClient chatClient(StubChatModel chatModel) {
            return ChatClient.builder(chatModel)
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build())
                    .build();
        }
    }

    /**
     * 手寫的假模型：記下收到的每個 Prompt，並讓每一題自己決定這次要怎麼回應。
     */
    static final class StubChatModel implements ChatModel {

        private static final Function<Prompt, String> DEFAULT = prompt -> "牛肉麵";

        Function<Prompt, String> behaviour = DEFAULT;
        final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(behaviour.apply(prompt)))));
        }

        void reset() {
            behaviour = DEFAULT;
            prompts.clear();
        }
    }
}
