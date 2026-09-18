import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Conversation")
class ConversationTest {

    private static Conversation.Message user(String text) {
        return new Conversation.Message(Conversation.Role.USER, text);
    }

    private static Conversation.Message assistant(String text) {
        return new Conversation.Message(Conversation.Role.ASSISTANT, text);
    }

    @Nested
    @DisplayName("開始對話")
    class Start {

        @Test
        @DisplayName("不帶系統指令：沒有指令、沒有訊息、不在等回覆")
        void startWithoutInstruction() {
            Conversation conversation = Conversation.start();

            assertTrue(conversation.instruction().isEmpty());
            assertTrue(conversation.messages().isEmpty());
            assertFalse(conversation.awaitingReply());
        }

        @Test
        @DisplayName("帶系統指令：原文保存")
        void startWithInstruction() {
            Conversation conversation = Conversation.start("你是營養師");

            assertEquals("你是營養師", conversation.instruction().orElseThrow());
        }

        @ParameterizedTest(name = "指令 = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("系統指令是 null 或空白時拒絕（規則 4）")
        void rejectBlankInstruction(String instruction) {
            assertThrows(IllegalArgumentException.class, () -> Conversation.start(instruction));
        }
    }

    @Nested
    @DisplayName("規則 1：提問與回覆必須交替")
    class Alternation {

        // 每個測試都會拿到新的 Alternation 實例，所以這個欄位不會在測試之間共用
        Conversation conversation = Conversation.start();

        @Nested
        @DisplayName("還沒提問")
        class NotAsked {

            @Test
            @DisplayName("提問後進入等待回覆")
            void askStartsWaiting() {
                conversation.ask("中午吃什麼");

                assertTrue(conversation.awaitingReply());
            }

            @Test
            @DisplayName("記錄回覆會被拒，歷史仍是空的")
            void rejectReply() {
                assertThrows(IllegalStateException.class, () -> conversation.recordReply("牛肉麵"));
                assertTrue(conversation.messages().isEmpty());
            }
        }

        @Nested
        @DisplayName("等待回覆中")
        class WaitingForReply {

            @BeforeEach
            void askFirst() {
                conversation.ask("中午吃什麼");
            }

            @Test
            @DisplayName("再提問會被拒，歷史不變")
            void rejectAsk() {
                assertThrows(IllegalStateException.class, () -> conversation.ask("再問一次"));
                assertEquals(List.of(user("中午吃什麼")), conversation.messages());
            }

            @Test
            @DisplayName("記錄回覆後結束等待")
            void replyEndsWaiting() {
                conversation.recordReply("牛肉麵");

                assertFalse(conversation.awaitingReply());
            }
        }

        @Nested
        @DisplayName("已回覆")
        class Replied {

            @BeforeEach
            void askAndReply() {
                conversation.ask("中午吃什麼");
                conversation.recordReply("牛肉麵");
            }

            @Test
            @DisplayName("再記錄一次回覆會被拒，歷史不變")
            void rejectSecondReply() {
                assertThrows(IllegalStateException.class, () -> conversation.recordReply("滷肉飯"));
                assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), conversation.messages());
            }

            @Test
            @DisplayName("可以開始下一輪，歷史依序記錄")
            void nextRoundKeepsOrder() {
                conversation.ask("熱量多少");
                conversation.recordReply("約 600 大卡");

                assertEquals(
                        List.of(user("中午吃什麼"), assistant("牛肉麵"), user("熱量多少"), assistant("約 600 大卡")),
                        conversation.messages());
            }
        }
    }

    @Nested
    @DisplayName("規則 2：系統指令是獨立欄位，不是訊息")
    class Instruction {

        @Test
        @DisplayName("系統指令不會出現在歷史訊息裡")
        void instructionIsNotAMessage() {
            Conversation conversation = Conversation.start("你是營養師");
            conversation.ask("中午吃什麼");

            assertEquals(List.of(user("中午吃什麼")), conversation.messages());
        }
    }

    @Nested
    @DisplayName("規則 3：歷史只能追加")
    class AppendOnly {

        @Test
        @DisplayName("從外部無法修改歷史訊息")
        void messagesCannotBeModified() {
            Conversation conversation = Conversation.start();
            conversation.ask("中午吃什麼");
            List<Conversation.Message> messages = conversation.messages();

            assertThrows(UnsupportedOperationException.class, () -> messages.add(assistant("偷塞的回覆")));
            assertThrows(UnsupportedOperationException.class, () -> messages.remove(0));
            assertThrows(UnsupportedOperationException.class, messages::clear);
        }

        @Test
        @DisplayName("先取得的快照不會被之後的對話改變")
        void snapshotDoesNotChange() {
            Conversation conversation = Conversation.start();
            conversation.ask("中午吃什麼");
            List<Conversation.Message> snapshot = conversation.messages();

            conversation.recordReply("牛肉麵");

            assertEquals(1, snapshot.size());
            assertEquals(2, conversation.messages().size());
        }
    }

    @Nested
    @DisplayName("規則 4：文字不可為 null 或空白")
    class TextRequired {

        @ParameterizedTest(name = "提問 = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("空白提問被拒，也不會進入等待回覆")
        void rejectBlankQuestion(String text) {
            Conversation conversation = Conversation.start();

            assertThrows(IllegalArgumentException.class, () -> conversation.ask(text));
            assertFalse(conversation.awaitingReply());
            assertTrue(conversation.messages().isEmpty());
        }

        @ParameterizedTest(name = "回覆 = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("空白回覆被拒，仍在等待回覆")
        void rejectBlankReply(String text) {
            Conversation conversation = Conversation.start();
            conversation.ask("中午吃什麼");

            assertThrows(IllegalArgumentException.class, () -> conversation.recordReply(text));
            assertTrue(conversation.awaitingReply());
        }

        @Test
        @DisplayName("直接建立 Message 也要遵守：角色不可為 null、文字不可空白")
        void messageValidatesItself() {
            assertThrows(IllegalArgumentException.class, () -> new Conversation.Message(null, "中午吃什麼"));
            assertThrows(IllegalArgumentException.class, () -> user("  "));
        }
    }
}
