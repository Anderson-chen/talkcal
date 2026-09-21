package adapter.in.console;

import application.domain.model.Conversation;
import application.domain.model.Reply;
import application.domain.service.AskQuestionService;
import application.port.in.AskQuestionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 餵 StringReader、收 StringWriter，所以不需要真的主控台就能測互動流程。
 * 這是把 IO 做成建構子參數換來的好處。
 */
@DisplayName("ConsoleChatAdapter")
class ConsoleChatAdapterTest {

    private final List<String> askedQuestions = new ArrayList<>();

    /** 假的 UseCase：記下被問了什麼，回一句固定的話 */
    private AskQuestionUseCase echoing(String answer) {
        return (conversation, question) -> {
            askedQuestions.add(question);
            return new Reply(answer);
        };
    }

    private String run(AskQuestionUseCase useCase, String input) {
        StringWriter output = new StringWriter();
        new ConsoleChatAdapter(useCase, new StringReader(input), output)
                .run(Conversation.start());
        return output.toString();
    }

    @Nested
    @DisplayName("對話流程")
    class Flow {

        @Test
        @DisplayName("問一句、印出回覆")
        void asksAndPrints() {
            String output = run(echoing("牛肉麵"), "晚餐吃什麼\nexit\n");

            assertEquals(List.of("晚餐吃什麼"), askedQuestions);
            assertTrue(output.contains("牛肉麵"), output);
        }

        @Test
        @DisplayName("連續問多句，順序保持")
        void handlesMultipleTurns() {
            run(echoing("好"), "第一句\n第二句\n第三句\nexit\n");

            assertEquals(List.of("第一句", "第二句", "第三句"), askedQuestions);
        }

        @Test
        @DisplayName("每一輪都先印提示符")
        void printsPrompt() {
            String output = run(echoing("好"), "問題\nexit\n");

            // 一次給問題、一次給 exit，所以提示符出現兩次
            assertEquals(2, output.chars().filter(c -> c == '>').count(), output);
        }

        @Test
        @DisplayName("中文原樣進出")
        void keepsChinese() {
            String output = run(echoing("好的，沒問題 😊"), "請用繁體中文\nexit\n");

            assertEquals(List.of("請用繁體中文"), askedQuestions);
            assertTrue(output.contains("好的，沒問題 😊"), output);
        }
    }

    @Nested
    @DisplayName("結束的方式")
    class Exiting {

        @Test
        @DisplayName("輸入 exit 離開")
        void exitsOnExitCommand() {
            String output = run(echoing("好"), "exit\n");

            assertTrue(askedQuestions.isEmpty(), "exit 不該被當成問題送出去");
            assertTrue(output.contains("結束對話"), output);
        }

        @Test
        @DisplayName("quit 和大小寫變化也認得")
        void acceptsQuitAndMixedCase() {
            run(echoing("好"), "QUIT\n");
            run(echoing("好"), "Exit\n");

            assertTrue(askedQuestions.isEmpty(), "都該被當成離開指令：" + askedQuestions);
        }

        @Test
        @DisplayName("輸入結束（EOF）時乾淨收工，不是當掉")
        void exitsOnEndOfInput() {
            String output = run(echoing("好"), "一句話\n");

            assertEquals(List.of("一句話"), askedQuestions);
            assertTrue(output.contains("結束對話"), output);
        }

        @Test
        @DisplayName("空行被忽略，不會結束對話也不會送出")
        void ignoresBlankLines() {
            run(echoing("好"), "\n   \n問題\nexit\n");

            assertEquals(List.of("問題"), askedQuestions);
        }
    }

    @Nested
    @DisplayName("呼叫失敗時")
    class Failures {

        @Test
        @DisplayName("印出原因，然後繼續問下一題")
        void reportsFailureAndContinues() {
            AskQuestionUseCase flaky = (conversation, question) -> {
                askedQuestions.add(question);
                if (askedQuestions.size() == 1) {
                    throw new IllegalStateException("連線逾時");
                }
                return new Reply("這次成功了");
            };

            String output = run(flaky, "第一次\n第二次\nexit\n");

            assertTrue(output.contains("連線逾時"), output);
            assertTrue(output.contains("這次成功了"), output);
            assertEquals(List.of("第一次", "第二次"), askedQuestions);
        }

        @Test
        @DisplayName("失敗後對話狀態乾淨，下一題送得出去")
        void conversationStaysUsableAfterFailure() {
            // 這題走真的 AskQuestionService，驗證它撤回提問之後對話真的可以繼續
            boolean[] firstCall = {true};
            AskQuestionUseCase useCase = new AskQuestionService((instruction, messages) -> {
                if (firstCall[0]) {
                    firstCall[0] = false;
                    throw new IllegalStateException("模型掛了");
                }
                return new Reply("復原了");
            });

            Conversation conversation = Conversation.start();
            StringWriter output = new StringWriter();
            new ConsoleChatAdapter(useCase, new StringReader("壞的\n好的\nexit\n"), output)
                    .run(conversation);

            assertTrue(output.toString().contains("復原了"), output.toString());
            // 一問一答，失敗那輪已經被撤掉，沒有留下垃圾
            assertEquals(2, conversation.messages().size(), "失敗的那一輪不該留在歷史裡");
            assertFalse(conversation.awaitingReply());
        }
    }

    @Nested
    @DisplayName("建構")
    class Construction {

        @Test
        @DisplayName("null 參數丟例外")
        void rejectsNulls() {
            assertThrows(NullPointerException.class,
                    () -> new ConsoleChatAdapter(null, new StringReader(""), new StringWriter()));
            assertThrows(NullPointerException.class,
                    () -> new ConsoleChatAdapter(echoing("好"), null, new StringWriter()));
            assertThrows(NullPointerException.class,
                    () -> new ConsoleChatAdapter(echoing("好"), new StringReader(""), null));
        }

        @Test
        @DisplayName("run 不收 null 對話")
        void rejectsNullConversation() {
            ConsoleChatAdapter adapter =
                    new ConsoleChatAdapter(echoing("好"), new StringReader(""), new StringWriter());

            assertThrows(NullPointerException.class, () -> adapter.run(null));
        }
    }
}
