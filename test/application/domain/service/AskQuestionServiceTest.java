package application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import application.domain.model.Conversation;
import application.domain.model.Reply;
import application.port.out.GenerateReplyPort;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AskQuestionService")
class AskQuestionServiceTest {

    private static Conversation.Message user(String text) {
        return new Conversation.Message(Conversation.Role.USER, text);
    }

    private static Conversation.Message assistant(String text) {
        return new Conversation.Message(Conversation.Role.ASSISTANT, text);
    }

    // 假模型：記下收到的內容，回傳固定的回覆
    private static final class RecordingPort implements GenerateReplyPort {
        Optional<String> instruction;
        List<Conversation.Message> messages;

        @Override
        public Reply generateReply(Optional<String> instruction, List<Conversation.Message> messages) {
            this.instruction = instruction;
            this.messages = messages;
            return new Reply("約 600 大卡");
        }
    }

    @Nested
    @DisplayName("模型成功回覆")
    class Success {

        @Test
        @DisplayName("回傳模型的回覆，並依序記進對話")
        void recordsQuestionAndReply() {
            Conversation conversation = Conversation.start();
            AskQuestionService service = new AskQuestionService((instruction, messages) -> new Reply("牛肉麵"));

            Reply reply = service.askQuestion(conversation, "中午吃什麼");

            assertEquals(new Reply("牛肉麵"), reply);
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), conversation.messages());
            assertFalse(conversation.awaitingReply());
        }

        @Test
        @DisplayName("送給模型的是系統指令，以及包含新提問的完整歷史")
        void sendsInstructionAndFullHistory() {
            Conversation conversation = Conversation.start("你是營養師");
            conversation.ask("中午吃什麼");
            conversation.recordReply("牛肉麵");
            RecordingPort port = new RecordingPort();

            new AskQuestionService(port).askQuestion(conversation, "熱量多少");

            assertEquals(Optional.of("你是營養師"), port.instruction);
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵"), user("熱量多少")), port.messages);
        }
    }

    @Nested
    @DisplayName("不合規的提問")
    class InvalidQuestion {

        @Test
        @DisplayName("空白提問被 model 拒絕，不會呼叫模型")
        void blankQuestionSkipsModel() {
            Conversation conversation = Conversation.start();
            AskQuestionService service = new AskQuestionService((instruction, messages) -> fail("不應該呼叫模型"));

            assertThrows(IllegalArgumentException.class, () -> service.askQuestion(conversation, "  "));
            assertEquals(List.of(), conversation.messages());
        }
    }

    @Nested
    @DisplayName("沒拿到可用的回覆時撤回提問")
    class Failure {

        Conversation conversation = Conversation.start();

        @Test
        @DisplayName("模型丟出例外：例外原樣往外丟，已回覆的輪次保留，這次的提問被撤回")
        void modelErrorWithdrawsQuestion() {
            conversation.ask("中午吃什麼");
            conversation.recordReply("牛肉麵");
            RuntimeException timeout = new IllegalStateException("連線逾時");
            AskQuestionService service = new AskQuestionService((instruction, messages) -> { throw timeout; });

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> service.askQuestion(conversation, "熱量多少"));

            assertSame(timeout, thrown);
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), conversation.messages());
            assertFalse(conversation.awaitingReply());
        }

        @Test
        @DisplayName("模型回覆空白（建立不了 Reply）：視同失敗，同樣撤回提問")
        void blankReplyWithdrawsQuestion() {
            AskQuestionService service = new AskQuestionService((instruction, messages) -> new Reply("  "));

            assertThrows(IllegalArgumentException.class, () -> service.askQuestion(conversation, "中午吃什麼"));
            assertEquals(List.of(), conversation.messages());
        }

        @Test
        @DisplayName("撤回後再問一次，就等於重試")
        void askAgainAfterFailure() {
            AskQuestionService failing = new AskQuestionService((instruction, messages) -> { throw new IllegalStateException("連線逾時"); });
            AskQuestionService working = new AskQuestionService((instruction, messages) -> new Reply("牛肉麵"));

            assertThrows(IllegalStateException.class, () -> failing.askQuestion(conversation, "中午吃什麼"));
            working.askQuestion(conversation, "中午吃什麼");

            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), conversation.messages());
        }
    }
}
