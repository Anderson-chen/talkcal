package eat.conversation.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.Reply;
import eat.conversation.application.port.in.AskQuestionUseCase;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ChatController 的測試，真的走一遍 Spring MVC。
 *
 * 為什麼不寫成純單元測試（new 一個 controller 直接呼叫方法）：
 * 這個類別幾乎每一行都是在跟 Spring 講話 —— 路由掛在哪、@RequestBody 怎麼綁、
 * 回應怎麼序列化、@ExceptionHandler 有沒有被接上。直接呼叫方法那些全部驗不到，
 * 只證明得了「有呼叫 use case」，而那本來就不是這個 adapter 值得擔心的事。
 *
 * @WebMvcTest 只載入 web 那一層：ConversationConfiguration 不會被建，
 * 所以不會有人去 new LlamaCppGenerateReplyAdapter，這些測試不需要任何外部服務。
 * AskQuestionUseCase 由下面的 TestConfiguration 用手寫的假物件補上 ——
 * starter-test 有帶 Mockito，但專案一律手寫假物件，這裡不破例。
 */
@WebMvcTest(ChatController.class)
// 明確 Import 而不是靠巢狀 TestConfiguration 被自動撿走：
// 那套自動偵測在 @WebMvcTest 這種切片測試上不吃，容器裡會少掉 AskQuestionUseCase，
// 六題會一起以 NoSuchBeanDefinitionException 倒掉。寫出來也比較看得出假物件從哪來
@Import(ChatControllerTest.StubConfiguration.class)
@DisplayName("ChatController")
class ChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StubAskQuestion askQuestion;

    @BeforeEach
    void resetStub() {
        // 容器（連同這個假物件）在同一個測試類別裡是共用的，每題自己重置才不會互相影響
        askQuestion.reset();
    }

    private static final String CHAT = "/api/chat";

    @Nested
    @DisplayName("正常路徑")
    class Success {

        @Test
        @DisplayName("把 JSON 裡的問題交給 use case，再把回覆包成 JSON")
        void returnsReplyAsJson() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"中午吃什麼\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reply").value("牛肉麵"));

            assertEquals(List.of("中午吃什麼"), askQuestion.questions);
        }

        @Test
        @DisplayName("每個請求都開一段全新的對話：目前刻意是無狀態單輪")
        void startsAFreshConversationPerRequest() throws Exception {
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(post(CHAT)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"中午吃什麼\"}"))
                        .andExpect(status().isOk());
            }

            assertEquals(2, askQuestion.conversations.size());
            assertNotSame(askQuestion.conversations.get(0), askQuestion.conversations.get(1),
                    "兩個請求共用了同一段對話");
            assertTrue(askQuestion.conversations.get(1).messages().isEmpty(),
                    "第二個請求拿到的對話不是全新的");
        }
    }

    @Nested
    @DisplayName("錯誤要翻成正確的 HTTP 語言")
    class Failures {

        @Test
        @DisplayName("空白提問是呼叫端送錯東西：400")
        void blankQuestionIsBadRequest() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"  \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("文字不可為 null 或空白"));
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
        @DisplayName("上游模型出事不是呼叫端的錯：502 而不是籠統的 500")
        void modelFailureIsBadGateway() throws Exception {
            askQuestion.behaviour = (conversation, question) -> {
                throw new IllegalStateException("呼叫 llama.cpp 失敗");
            };

            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"中午吃什麼\"}"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error").value("呼叫 llama.cpp 失敗"));
        }

        /**
         * 回歸測試，守著 ChatController 註解裡記的那個坑：
         * 一開始 onModelFailure 攔的是整個 RuntimeException，結果連 Spring 自己因為
         * 「JSON 格式不對」丟的 HttpMessageNotReadableException 都被吃進來、誤標成 502 ——
         * 那明明是呼叫端送錯，該 400。
         *
         * 註解記得住當初為什麼收窄成 IllegalStateException，但攔不住下一次有人改回去。
         * 這題會。
         */
        @Test
        @DisplayName("JSON 本身壞掉：400 而不是 502，而且根本不該驚動 use case")
        void malformedJsonIsBadRequestNotBadGateway() throws Exception {
            mockMvc.perform(post(CHAT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\": 這不是合法的 JSON"))
                    .andExpect(status().isBadRequest());

            assertTrue(askQuestion.questions.isEmpty(), "請求都還沒讀懂就不該呼叫 core");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        StubAskQuestion askQuestion() {
            return new StubAskQuestion();
        }
    }

    /**
     * 手寫的假 use case：記下收到什麼，並讓每一題自己決定這次要怎麼回應。
     */
    static final class StubAskQuestion implements AskQuestionUseCase {

        // 預設行為照著核心真正的規則走：空白提問由 Conversation 以 IllegalArgumentException 擋下。
        // 真正的判斷不在這裡（controller 只負責把例外翻成 HTTP），
        // 但假物件照著同一條規則演，測試才不會描述一個現實中不存在的情境
        private static final BiFunction<Conversation, String, Reply> DEFAULT =
                (conversation, question) -> {
                    if (question == null || question.isBlank()) {
                        throw new IllegalArgumentException("文字不可為 null 或空白");
                    }
                    return new Reply("牛肉麵");
                };

        BiFunction<Conversation, String, Reply> behaviour = DEFAULT;
        final List<Conversation> conversations = new ArrayList<>();
        final List<String> questions = new ArrayList<>();

        @Override
        public Reply askQuestion(Conversation conversation, String question) {
            conversations.add(conversation);
            questions.add(question);
            return behaviour.apply(conversation, question);
        }

        void reset() {
            behaviour = DEFAULT;
            conversations.clear();
            questions.clear();
        }
    }
}
