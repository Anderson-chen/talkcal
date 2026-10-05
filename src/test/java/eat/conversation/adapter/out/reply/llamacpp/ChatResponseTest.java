package eat.conversation.adapter.out.reply.llamacpp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ChatResponse")
class ChatResponseTest {

    @Nested
    @DisplayName("取出模型講的話")
    class Text {

        @Test
        @DisplayName("從 choices[0].message.content 取出文字")
        void readsContent() {
            String json = "{\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"2 + 2 = 4.\"}}],"
                    + "\"model\":\"qwen3-8b\",\"usage\":{\"completion_tokens\":5}}";

            assertEquals("2 + 2 = 4.", ChatResponse.text(json));
        }

        @Test
        @DisplayName("跳脫與中文都正確還原")
        void restoresEscapesAndChinese() {
            String json = "{\"choices\":[{\"message\":{\"content\":"
                    + "\"他說\\\"你好\\\"\\n第二行 \\ud83d\\ude0a\"}}]}";

            assertEquals("他說\"你好\"\n第二行 😊", ChatResponse.text(json));
        }

        @Test
        @DisplayName("忽略 reasoning_content，思考過程不進對話歷史")
        void ignoresReasoningContent() {
            // 這是 thinking 開著時的真實形狀
            String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"2 + 2 = 4.\","
                    + "\"reasoning_content\":\"Okay, the user asked 2+2. Let me think.\"}}]}";

            assertEquals("2 + 2 = 4.", ChatResponse.text(json));
        }

        @Test
        @DisplayName("被截斷的回覆照常回傳，內容仍然有用")
        void keepsTruncatedContent() {
            // llama-server 在 max_tokens 用完時就是這個樣子
            String json = "{\"choices\":[{\"finish_reason\":\"length\","
                    + "\"message\":{\"content\":\"One, two, three\"}}]}";

            assertEquals("One, two, three", ChatResponse.text(json));
        }
    }

    @Nested
    @DisplayName("回應不能用時要丟例外")
    class Failures {

        @Test
        @DisplayName("server 回報錯誤時，原封不動帶出它的訊息")
        void surfacesServerError() {
            String json = "{\"error\":{\"code\":500,\"message\":\"ill-formed UTF-8 byte\","
                    + "\"type\":\"server_error\"}}";

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text(json));
            assertTrue(thrown.getMessage().contains("ill-formed UTF-8 byte"),
                    "應該帶出 server 的訊息：" + thrown.getMessage());
        }

        @Test
        @DisplayName("error 格式不如預期時也不會再炸一次，蓋掉原本的錯誤")
        void toleratesOddErrorShape() {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{\"error\":\"壞掉了\"}"));

            assertTrue(thrown.getMessage().contains("壞掉了"), thrown.getMessage());
        }

        @Test
        @DisplayName("模型沒有產生內容")
        void rejectsBlankContent() {
            // Reply 不收空白。在這裡擋掉，訊息才看得出兇手是誰
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"));

            assertTrue(thrown.getMessage().contains("沒有產生"), thrown.getMessage());
        }

        @Test
        @DisplayName("結構缺東缺西")
        void rejectsMissingParts() {
            assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{}"));
            assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{\"choices\":[]}"));
            assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{\"choices\":[{}]}"));
            assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{\"choices\":[{\"message\":{}}]}"));
        }

        @Test
        @DisplayName("content 不是字串")
        void rejectsNonStringContent() {
            assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{\"choices\":[{\"message\":{\"content\":42}}]}"));
        }

        @Test
        @DisplayName("整包不是 JSON 物件")
        void rejectsNonObjectRoot() {
            assertThrows(IllegalStateException.class, () -> ChatResponse.text("[]"));
        }

        @Test
        @DisplayName("被截斷的 JSON 在剖析階段就炸，而且算上游的錯（IllegalStateException → 502），不是呼叫端的錯（400）")
        void rejectsTruncatedJson() {
            assertThrows(IllegalStateException.class,
                    () -> ChatResponse.text("{\"choices\":[{\"message\":{\"content\":\"半"));
        }
    }
}
