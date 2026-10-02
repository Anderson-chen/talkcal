package eat.conversation.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.ConversationChangedException;
import eat.conversation.application.domain.model.ConversationId;
import eat.conversation.application.domain.model.Passage;
import eat.conversation.application.domain.model.Reply;
import eat.conversation.application.port.in.Answer;
import eat.conversation.application.port.in.ConversationNotFoundException;
import eat.conversation.application.port.out.GenerateReplyPort;
import eat.conversation.application.port.out.LoadConversationPort;
import eat.conversation.application.port.out.RetrievePassagesPort;
import eat.conversation.application.port.out.SaveConversationPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AskQuestionService")
class AskQuestionServiceTest {

    // 假知識庫：什麼都找不到。不關心檢索的測試用這個，
    // 因為沒有片段時送出的就是原始提問，等同於還沒導入 RAG 的行為。
    private static final RetrievePassagesPort NOTHING_FOUND = question -> List.of();

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

    /**
     * 假資料庫：存在 Map 裡，存的是「歷史的快照」而不是物件本身 ——
     * 跟真的資料庫一樣，存完之後再改那個物件，也改不到存起來的東西。
     * 讀出來時用 Conversation.restore 重建，版本號加一，跟真的 adapter 行為一致。
     */
    private static final class InMemoryConversations implements LoadConversationPort, SaveConversationPort {
        private record Stored(String instruction, List<Conversation.Message> messages, long version) {
        }

        final Map<ConversationId, Stored> stored = new HashMap<>();
        int saves;

        @Override
        public Optional<Conversation> load(ConversationId id) {
            return Optional.ofNullable(stored.get(id))
                    .map(s -> Conversation.restore(id, s.instruction(), s.messages(), s.version()));
        }

        @Override
        public void save(Conversation conversation) {
            saves++;
            stored.put(conversation.id(), new Stored(conversation.instruction().orElse(null),
                    conversation.messages(), conversation.version() + 1));
        }

        // 測試開場用：先放一段已經存在的對話
        ConversationId existing(Conversation conversation) {
            save(conversation);
            saves = 0;
            return conversation.id();
        }

        List<Conversation.Message> messagesOf(ConversationId id) {
            return stored.get(id).messages();
        }
    }

    private final InMemoryConversations conversations = new InMemoryConversations();

    private AskQuestionService service(RetrievePassagesPort retrieve, GenerateReplyPort generate) {
        return new AskQuestionService(conversations, retrieve, generate, conversations);
    }

    @Nested
    @DisplayName("模型成功回覆")
    class Success {

        @Test
        @DisplayName("沒帶 ID：開一段新對話，問答依序存起來，回傳它的 ID")
        void startsAndSavesNewConversation() {
            Answer answer = service(NOTHING_FOUND, (instruction, messages) -> new Reply("牛肉麵"))
                    .askQuestion(Optional.empty(), "中午吃什麼");

            assertEquals(new Reply("牛肉麵"), answer.reply());
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), conversations.messagesOf(answer.conversationId()));
        }

        @Test
        @DisplayName("帶著 ID：接續那一段，模型看得到系統指令和之前的問答")
        void continuesExistingConversation() {
            Conversation earlier = Conversation.start("你是營養師");
            earlier.ask("中午吃什麼");
            earlier.recordReply("牛肉麵");
            ConversationId id = conversations.existing(earlier);
            RecordingPort port = new RecordingPort();

            Answer answer = service(NOTHING_FOUND, port).askQuestion(Optional.of(id), "熱量多少");

            assertEquals(id, answer.conversationId());
            assertEquals(Optional.of("你是營養師"), port.instruction);
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵"), user("熱量多少")), port.messages);
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵"), user("熱量多少"), assistant("約 600 大卡")),
                    conversations.messagesOf(id));
        }

        @Test
        @DisplayName("帶著不存在的 ID：明說找不到，不默默開新對話，檢索和模型都不會被呼叫")
        void unknownConversationIsNotFound() {
            ConversationId unknown = ConversationId.newId();
            AskQuestionService service = service(
                    question -> fail("不應該呼叫檢索"),
                    (instruction, messages) -> fail("不應該呼叫模型"));

            assertThrows(ConversationNotFoundException.class,
                    () -> service.askQuestion(Optional.of(unknown), "熱量多少"));
            assertEquals(0, conversations.saves);
        }
    }

    @Nested
    @DisplayName("先檢索再提問")
    class Retrieval {

        @Test
        @DisplayName("檢索到的片段帶著出處一起送給模型")
        void sendsGroundedQuestion() {
            RecordingPort port = new RecordingPort();
            RetrievePassagesPort found = question ->
                    List.of(new Passage("中火煎四分鐘再翻面。", "鮭魚.md > 烹調建議"));

            service(found, port).askQuestion(Optional.empty(), "鮭魚要煎幾分鐘？");

            assertEquals(List.of(user("""
                    參考資料：
                    [1]（鮭魚.md > 烹調建議）中火煎四分鐘再翻面。

                    請依據上述參考資料回答，資料中沒有提到的內容不要自行推測。

                    問題：鮭魚要煎幾分鐘？""")), port.messages);
        }

        @Test
        @DisplayName("歷史記的是原本的提問，參考資料不進歷史")
        void historyKeepsPlainQuestion() {
            RetrievePassagesPort found = question ->
                    List.of(new Passage("中火煎四分鐘再翻面。", "鮭魚.md > 烹調建議"));

            Answer answer = service(found, (instruction, messages) -> new Reply("四分鐘"))
                    .askQuestion(Optional.empty(), "鮭魚要煎幾分鐘？");

            assertEquals(List.of(user("鮭魚要煎幾分鐘？"), assistant("四分鐘")), conversations.messagesOf(answer.conversationId()));
        }

        @Test
        @DisplayName("下一輪只帶這一輪的參考資料，舊的不會跟著送出去")
        void earlierPassagesAreNotResent() {
            Conversation earlier = Conversation.start();
            earlier.ask("鮭魚要煎幾分鐘？");
            earlier.recordReply("四分鐘");
            ConversationId id = conversations.existing(earlier);
            RecordingPort port = new RecordingPort();
            RetrievePassagesPort found = question ->
                    List.of(new Passage("每百克約 23 克蛋白質。", "雞胸肉.md > 營養成分"));

            service(found, port).askQuestion(Optional.of(id), "雞胸肉蛋白質多少？");

            assertEquals(user("鮭魚要煎幾分鐘？"), port.messages.getFirst());
            assertTrue(port.messages.getLast().text().contains("雞胸肉.md"));
        }

        @Test
        @DisplayName("什麼都沒檢索到時，送出的就是原始提問")
        void sendsPlainQuestionWhenNothingFound() {
            RecordingPort port = new RecordingPort();

            service(NOTHING_FOUND, port).askQuestion(Optional.empty(), "鮭魚要煎幾分鐘？");

            assertEquals(List.of(user("鮭魚要煎幾分鐘？")), port.messages);
        }

        @Test
        @DisplayName("檢索失敗：例外原樣往外丟，不呼叫模型，也什麼都沒存")
        void retrievalFailureSavesNothing() {
            RuntimeException unreachable = new IllegalStateException("檢索服務連不上");
            AskQuestionService service = service(
                    question -> { throw unreachable; },
                    (instruction, messages) -> fail("不應該呼叫模型"));

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> service.askQuestion(Optional.empty(), "鮭魚要煎幾分鐘？"));

            assertSame(unreachable, thrown);
            assertEquals(0, conversations.saves);
        }
    }

    @Nested
    @DisplayName("不合規的提問")
    class InvalidQuestion {

        @Test
        @DisplayName("空白提問被 model 拒絕，讀檔、檢索、模型、存檔都不會發生")
        void blankQuestionTouchesNothing() {
            // 檢索也要換成「一被呼叫就失敗」的版本，不能用 NOTHING_FOUND：
            // NOTHING_FOUND 對空白字串照樣回空清單，檢索有沒有先跑，這題根本看不出來。
            AskQuestionService service = new AskQuestionService(
                    id -> fail("不應該讀檔"),
                    question -> fail("不應該呼叫檢索"),
                    (instruction, messages) -> fail("不應該呼叫模型"),
                    conversation -> fail("不應該存檔"));

            assertThrows(IllegalArgumentException.class,
                    () -> service.askQuestion(Optional.of(ConversationId.newId()), "  "));
        }
    }

    @Nested
    @DisplayName("沒拿到可用的回覆時什麼都不存")
    class Failure {

        @Test
        @DisplayName("模型丟出例外：例外原樣往外丟，存起來的對話維持原樣")
        void modelErrorSavesNothing() {
            Conversation earlier = Conversation.start();
            earlier.ask("中午吃什麼");
            earlier.recordReply("牛肉麵");
            ConversationId id = conversations.existing(earlier);
            RuntimeException timeout = new IllegalStateException("連線逾時");

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> service(NOTHING_FOUND, (instruction, messages) -> { throw timeout; })
                            .askQuestion(Optional.of(id), "熱量多少"));

            assertSame(timeout, thrown);
            assertEquals(0, conversations.saves);
            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), conversations.messagesOf(id));
        }

        @Test
        @DisplayName("模型回覆空白（建立不了 Reply）：視同失敗，同樣不存")
        void blankReplySavesNothing() {
            assertThrows(IllegalArgumentException.class,
                    () -> service(NOTHING_FOUND, (instruction, messages) -> new Reply("  "))
                            .askQuestion(Optional.empty(), "中午吃什麼"));
            assertEquals(0, conversations.saves);
        }

        @Test
        @DisplayName("失敗後再問一次，就等於重試")
        void askAgainAfterFailure() {
            ConversationId id = conversations.existing(Conversation.start());

            assertThrows(IllegalStateException.class,
                    () -> service(NOTHING_FOUND, (instruction, messages) -> { throw new IllegalStateException("連線逾時"); })
                            .askQuestion(Optional.of(id), "中午吃什麼"));
            service(NOTHING_FOUND, (instruction, messages) -> new Reply("牛肉麵"))
                    .askQuestion(Optional.of(id), "中午吃什麼");

            assertEquals(List.of(user("中午吃什麼"), assistant("牛肉麵")), conversations.messagesOf(id));
        }

        @Test
        @DisplayName("存檔時發現對話被別人先改了：例外原樣往外丟，讓 HTTP 那頭回 409")
        void saveConflictPropagates() {
            ConversationChangedException conflict = new ConversationChangedException(ConversationId.newId());
            AskQuestionService service = new AskQuestionService(conversations, NOTHING_FOUND,
                    (instruction, messages) -> new Reply("牛肉麵"),
                    conversation -> { throw conflict; });

            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> service.askQuestion(Optional.empty(), "中午吃什麼"));

            assertSame(conflict, thrown);
        }
    }
}
