package eat.conversation.adapter.out.reply.llamacpp;

import eat.conversation.application.domain.model.Conversation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static eat.conversation.application.domain.model.Conversation.Role.ASSISTANT;
import static eat.conversation.application.domain.model.Conversation.Role.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChatRequest")
class ChatRequestTest {

    private static Conversation.Message user(String text) {
        return new Conversation.Message(USER, text);
    }

    private static Conversation.Message assistant(String text) {
        return new Conversation.Message(ASSISTANT, text);
    }

    @Nested
    @DisplayName("組出 request body")
    class Body {

        @Test
        @DisplayName("單一提問組成一則 user 訊息")
        void singleQuestion() {
            String body = ChatRequest.body(Optional.empty(), List.of(user("hi")));

            assertEquals("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}", body);
        }

        @Test
        @DisplayName("系統指令攤平成第一則 system 訊息")
        void instructionBecomesLeadingSystemMessage() {
            String body = ChatRequest.body(Optional.of("你是助理"), List.of(user("hi")));

            assertEquals("{\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"你是助理\"},"
                    + "{\"role\":\"user\",\"content\":\"hi\"}"
                    + "],\"stream\":false}", body);
        }

        @Test
        @DisplayName("沒有系統指令時不會憑空生出 system 訊息")
        void omitsSystemMessageWhenNoInstruction() {
            String body = ChatRequest.body(Optional.empty(), List.of(user("hi")));

            assertTrue(body.contains("\"user\""));
            assertTrue(!body.contains("system"), "不該出現 system");
        }

        @Test
        @DisplayName("多輪對話依原順序攤平，角色正確對應")
        void keepsTurnOrderAndMapsRoles() {
            String body = ChatRequest.body(Optional.empty(),
                    List.of(user("一"), assistant("二"), user("三")));

            assertEquals("{\"messages\":["
                    + "{\"role\":\"user\",\"content\":\"一\"},"
                    + "{\"role\":\"assistant\",\"content\":\"二\"},"
                    + "{\"role\":\"user\",\"content\":\"三\"}"
                    + "],\"stream\":false}", body);
        }

        @Test
        @DisplayName("訊息內的特殊字元有被跳脫，body 不會壞掉")
        void escapesContent() {
            String body = ChatRequest.body(Optional.empty(), List.of(user("say \"hi\"\n")));

            assertEquals("{\"messages\":["
                    + "{\"role\":\"user\",\"content\":\"say \\\"hi\\\"\\n\"}"
                    + "],\"stream\":false}", body);
        }

        @Test
        @DisplayName("不帶 model 欄位：server 單模型常駐，寫了也會被忽略")
        void omitsModelField() {
            String body = ChatRequest.body(Optional.empty(), List.of(user("hi")));

            assertTrue(!body.contains("model"), "不該出現 model 欄位");
        }

        @Test
        @DisplayName("不帶取樣參數：server 啟動時已設好，adapter 不越權覆蓋")
        void omitsSamplingParameters() {
            String body = ChatRequest.body(Optional.empty(), List.of(user("hi")));

            assertTrue(!body.contains("temperature"), "不該出現 temperature");
            assertTrue(!body.contains("top_p"), "不該出現 top_p");
        }

        @Test
        @DisplayName("空的訊息清單代表呼叫端違約，當場丟例外")
        void rejectsEmptyMessages() {
            assertThrows(IllegalArgumentException.class,
                    () -> ChatRequest.body(Optional.empty(), List.of()));
        }

        @Test
        @DisplayName("null 參數丟例外")
        void rejectsNulls() {
            assertThrows(NullPointerException.class,
                    () -> ChatRequest.body(null, List.of(user("hi"))));
            assertThrows(NullPointerException.class,
                    () -> ChatRequest.body(Optional.empty(), null));
        }
    }
}
