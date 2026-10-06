package eat.calendar.adapter.in.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.calendar.application.domain.model.CalendarEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 不打真模型：一個照劇本回話的假 ChatModel，看 agent loop 本身對不對 —— 工具有沒有被執行、
 * 記憶裡有沒有留著工具呼叫、截斷會不會拆散呼叫和結果、繞圈會不會停。
 *
 * 模型「會不會」反問、選不選得對工具，是 integrationTest 的 CalendarAssistantConversationTest 對真模型測的。
 */
@DisplayName("CalendarAssistant")
class CalendarAssistantTest {

    // 台北 2026-10-06（週二）11:30
    private static final Clock TUE = Clock.fixed(Instant.parse("2026-10-06T03:30:00Z"), ZoneId.of("Asia/Taipei"));
    private static final String ID = "3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10";
    private static final CalendarEvent DINNER = new CalendarEvent("吃飯", LocalDateTime.of(2026, 10, 7, 19, 0), LocalDateTime.of(2026, 10, 7, 20, 0));

    private final ChatMemoryRepository memory = new InMemoryChatMemoryRepository();
    private final List<Prompt> prompts = new ArrayList<>();
    private final List<String> parsed = new ArrayList<>();

    /** 照劇本回話：每呼叫一次拿下一句；劇本用完就一直回最後一句。 */
    private ChatModel scripted(AssistantMessage... script) {
        Deque<AssistantMessage> lines = new ArrayDeque<>(List.of(script));
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                AssistantMessage line = lines.size() > 1 ? lines.poll() : lines.peek();
                return new ChatResponse(List.of(new Generation(line)));
            }
        };
    }

    private static AssistantMessage says(String text) {
        return new AssistantMessage(text);
    }

    private static AssistantMessage proposes(String description) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + Math.abs(description.hashCode()), "function", "propose_events",
                        "{\"description\":\"" + description + "\"}")))
                .build();
    }

    private CalendarAssistant assistant(ChatModel model) {
        return new CalendarAssistant(model, memory,
                description -> {
                    parsed.add(description);
                    return List.of(DINNER);
                },
                (start, end) -> List.of(), (start, end, period, minimum) -> List.of(), TUE);
    }

    @Test
    @DisplayName("模型直接回話：回那句話，沒有提議，只呼叫模型一次")
    void plainReply() {
        CalendarAssistant.Reply reply = assistant(scripted(says("早上還是晚上？"))).reply(ID, "明天七點吃飯");

        assertEquals("早上還是晚上？", reply.text());
        assertTrue(reply.proposals().isEmpty());
        assertEquals(1, prompts.size());
    }

    @Test
    @DisplayName("模型要呼叫工具：執行 propose_events（交給解析），再問一次模型；提議的行程交回畫面")
    void runsToolsThenAnswers() {
        CalendarAssistant.Reply reply = assistant(scripted(proposes("明天晚上七點吃飯"), says("請確認卡片。"))).reply(ID, "晚上");

        assertEquals(List.of("明天晚上七點吃飯"), parsed);
        assertEquals(List.of(DINNER), reply.proposals());
        assertEquals("請確認卡片。", reply.text());
        // 第二次呼叫模型時，帶著工具的結果
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).getInstructions().stream().anyMatch(m -> m instanceof ToolResponseMessage));
    }

    @Test
    @DisplayName("回報的 bug：記憶裡留著工具呼叫和結果，下一輪模型看得到「上次是呼叫工具才有卡片」")
    void memoryKeepsToolCalls() {
        CalendarAssistant assistant = assistant(scripted(says("早上還是晚上？"), proposes("明天晚上七點吃飯"), says("請確認卡片。"), says("好")));
        assistant.reply(ID, "明天七點吃飯");
        assistant.reply(ID, "晚上");

        List<MessageType> stored = memory.findByConversationId(ID).stream().map(Message::getMessageType).toList();
        assertEquals(List.of(MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.ASSISTANT, MessageType.TOOL,
                MessageType.ASSISTANT), stored);
        assertTrue(((AssistantMessage) memory.findByConversationId(ID).get(3)).hasToolCalls());

        assistant.reply(ID, "其實是早上");

        // 第三輪送給模型的歷史裡，有上一輪的工具呼叫
        assertTrue(prompts.getLast().getInstructions().stream()
                .anyMatch(m -> m instanceof AssistantMessage a && a.hasToolCalls()), "歷史裡沒有工具呼叫");
    }

    @Test
    @DisplayName("system 每一輪重給（帶著「現在」和日期表），但不存進記憶")
    void systemIsFreshAndNotStored() {
        assistant(scripted(says("好"))).reply(ID, "明天有什麼行程？");

        Message first = prompts.getFirst().getInstructions().getFirst();
        assertEquals(MessageType.SYSTEM, first.getMessageType());
        assertTrue(first.getText().contains("現在是 2026-10-06 11:30"), first.getText());
        assertTrue(memory.findByConversationId(ID).stream().noneMatch(m -> m.getMessageType() == MessageType.SYSTEM));
    }

    @Test
    @DisplayName("模型一直要工具、不回話：超過步數就停，IllegalStateException，不存記憶")
    void stopsWhenLooping() {
        assertThrows(IllegalStateException.class, () -> assistant(scripted(proposes("明天晚上七點吃飯"))).reply(ID, "晚上"));

        assertEquals(CalendarAssistant.MAX_STEPS, prompts.size());
        assertTrue(memory.findByConversationId(ID).isEmpty());
    }

    @Test
    @DisplayName("recentTurns：只留最近幾輪，而且從「使用者的話」截，不拆散工具呼叫和結果")
    void trimsAtTurnBoundaries() {
        List<Message> conversation = new ArrayList<>();
        for (int turn = 0; turn < CalendarAssistant.MAX_TURNS + 2; turn++) {
            conversation.add(new UserMessage("第 " + turn + " 句"));
            conversation.add(proposes("第 " + turn + " 個行程"));
            conversation.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("call", "propose_events", "已顯示"))).build());
            conversation.add(says("請確認"));
        }

        List<Message> kept = CalendarAssistant.recentTurns(conversation);

        assertEquals(CalendarAssistant.MAX_TURNS * 4, kept.size());
        assertEquals(MessageType.USER, kept.getFirst().getMessageType());
        assertEquals("第 2 句", kept.getFirst().getText());
    }

    @Test
    @DisplayName("訊息空白：IllegalArgumentException，不打模型")
    void rejectsBlankMessage() {
        assertThrows(IllegalArgumentException.class, () -> assistant(scripted(says("x"))).reply(ID, " "));
        assertTrue(prompts.isEmpty());
    }

    @Test
    @DisplayName("模型出事（任何例外，連 IllegalArgumentException 也是）：包成 IllegalStateException → 502")
    void wrapsUpstreamFailure() {
        ChatModel broken = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalArgumentException("SDK 裡面的參數錯誤");
            }
        };

        assertThrows(IllegalStateException.class, () -> assistant(broken).reply(ID, "嗨"));
    }

    @Test
    @DisplayName("模型回了空白：IllegalStateException，而且不存進記憶（不然下一輪會照著學）")
    void blankReply() {
        assertThrows(IllegalStateException.class, () -> assistant(scripted(says("  "))).reply(ID, "嗨"));
        assertTrue(memory.findByConversationId(ID).isEmpty());
    }

    @Test
    @DisplayName("instruction：日期表跟著傳進來的「現在」走")
    void instructionFollowsNow() {
        String text = CalendarAssistant.instruction(LocalDateTime.of(2026, 10, 11, 20, 0));

        assertTrue(text.contains("現在是 2026-10-11 20:00"));
        assertTrue(text.contains("2026-10-12 = 下週一 = 明天"));
    }

    @Test
    @DisplayName("history：只回給人看的（使用者的話、助理的回覆），工具呼叫和結果濾掉")
    void historyShowsOnlyWhatPeopleSaid() {
        CalendarAssistant assistant = assistant(scripted(says("早上還是晚上？"), proposes("明天晚上七點吃飯"), says("請確認卡片。")));
        assistant.reply(ID, "明天七點吃飯");
        assistant.reply(ID, "晚上");

        assertEquals(List.of(
                new CalendarAssistant.Line(true, "明天七點吃飯"),
                new CalendarAssistant.Line(false, "早上還是晚上？"),
                new CalendarAssistant.Line(true, "晚上"),
                new CalendarAssistant.Line(false, "請確認卡片。")), assistant.history(ID));
    }

    @Test
    @DisplayName("forget：清空之後讀回來是空的，下一句是全新的對話；本來就沒有也不算錯")
    void forgetClearsTheConversation() {
        CalendarAssistant assistant = assistant(scripted(says("好")));
        assistant.reply(ID, "明天有什麼行程？");

        assistant.forget(ID);
        assistant.forget(ID);

        assertTrue(assistant.history(ID).isEmpty());
        assistant.reply(ID, "嗨");
        // 清空後的第一句：送給模型的只有 system 和這一句
        assertEquals(2, prompts.getLast().getInstructions().size());
    }
}

