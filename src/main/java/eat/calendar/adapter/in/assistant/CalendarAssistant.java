package eat.calendar.adapter.in.assistant;

import eat.calendar.adapter.shared.DateTable;
import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.port.in.FindFreeSlotsUseCase;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.ParseEventsUseCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 行事曆的 AI 助理：一段對話，模型自己決定要查行程、找空檔、提議新增，還是先反問使用者。
 *
 * 分工是實測出來的（qwen3:8b，不 thinking）：
 * - 模型擅長的交給模型：判斷使用者想做什麼、時間不確定時反問、把對話整理成一句完整的話、轉述查詢結果
 * - 模型不擅長的不交給它：時間怎麼解析（propose_events 只收一句話，交給既有的抽取流程）、
 *   空檔怎麼算（FreeSlots）、「下午」是幾點到幾點（DayPeriod）、存不存檔（使用者按確認）
 *
 * 模型的話不全信：沒呼叫工具卻說「已顯示卡片」「已加入」時，提醒它重來一次，還是一樣就換成老實的固定回覆（見 reply）。
 *
 * agent loop 寫在這裡（reply），不交給 Spring AI 的 ChatClient + advisor：
 * 記憶裡一定要留著工具呼叫和工具結果。只留「使用者的話」和「助理最後那句」時，
 * 下一輪模型看到的是「助理說了『已顯示卡片』就完成了」，於是改時間時只說不做、畫面上沒有卡片（使用者實際遇到的 bug）。
 * Spring AI 的 JDBC 記憶庫會把工具訊息濾掉，MessageChatMemoryAdvisor 也只存頭尾兩則，
 * 自己跑迴圈，每一則訊息都在手上，存什麼、截到哪都看得見。
 */
public final class CalendarAssistant {

    // agent 內部做了什麼（叫了哪個工具、帶什麼參數、有沒有說謊被擋下）。access log 只看得到「HTTP 200、回了一段話」，
    // 模型只說不做時從外面看不出來 —— 使用者回報「說已加入卻沒有卡片」時，log 裡什麼都沒有
    private static final Logger log = LoggerFactory.getLogger(CalendarAssistant.class);

    /**
     * 回覆裡在說「有卡片」或「已經加入」。這一輪明明沒有提議任何行程，卻這樣說，就是在說謊：
     * 實際發生過 ——「隨便」→「已顯示卡片」（沒呼叫工具）、「是」→「已加入這週三寫日記的行程」（助理根本不能加入）。
     *
     * 前面是「是否、有沒有、還沒、尚未、未、不」的不算：「我無法確認是否已加入」是在問、不是在宣稱（實際被誤擋過）。
     */
    private static final Pattern CLAIMS_ACTION = Pattern.compile(
            "卡片|(?<!是否|有沒有|沒有|還沒|尚未|未|不)(已加入|已新增|已安排|已經(加入|新增|安排))|幫你(加入|新增)了");

    // 抓到說謊時，暫時跟模型說的話：只放在這一次的請求裡，不存進記憶
    static final String CORRECTION = "（系統提醒，不是使用者說的）你剛才的回覆提到了卡片或已加入，但這一輪你沒有呼叫 propose_events："
            + "畫面上沒有任何新卡片，行程也沒有被加入 —— 你沒辦法加入行事曆，只有使用者按卡片上的「加入行事曆」才會加入。"
            + "要新增行程就呼叫 propose_events；資訊不夠就問使用者；不然就照實回答，不要提卡片。";

    // 提醒過還是一樣：不再讓模型說話，改成老實的固定回覆。存進記憶的也是這句，不是那句假話
    static final String HONEST_FALLBACK = "抱歉，我剛剛沒有真的建立卡片。請把要新增的行程再說一次（日期、時間、要做的事），我會整理成卡片讓你確認。";

    // 溫度 0：同一段對話每次走同一條路，壞了重現得出來。實測 0 之下該問的會問、工具也選得對
    private static final double TEMPERATURE = 0;
    // 一次呼叫的上限。回覆本身很短，工具呼叫的參數也不長；超過就是模型停不下來了
    private static final int MAX_TOKENS = 1024;
    // 一句話最多跑幾步（呼叫模型的次數）。正常是兩步：呼叫工具 → 根據結果回話；超過就是在繞圈
    static final int MAX_STEPS = 4;
    // 記憶只留最近幾輪（一輪 = 使用者一句話，加上之後的工具呼叫和回覆）。
    // 以輪為單位截：從中間截會留下沒有前頭呼叫的工具結果，API 會直接拒收
    static final int MAX_TURNS = 10;
    private static final DateTimeFormatter NOW_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ChatModel chatModel;
    private final ChatMemoryRepository memory;
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
    private final ParseEventsUseCase parseEvents;
    private final ListEventsUseCase listEvents;
    private final FindFreeSlotsUseCase findFreeSlots;
    private final Clock clock;

    public CalendarAssistant(ChatModel chatModel, ChatMemoryRepository memory, ParseEventsUseCase parseEvents,
                             ListEventsUseCase listEvents, FindFreeSlotsUseCase findFreeSlots, Clock clock) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel 不可為 null");
        this.memory = Objects.requireNonNull(memory, "memory 不可為 null");
        this.parseEvents = Objects.requireNonNull(parseEvents, "parseEvents 不可為 null");
        this.listEvents = Objects.requireNonNull(listEvents, "listEvents 不可為 null");
        this.findFreeSlots = Objects.requireNonNull(findFreeSlots, "findFreeSlots 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    /** 助理這一輪說的話，以及這一輪提議的行程（畫面顯示成卡片，使用者確認後才存）。 */
    public record Reply(String text, List<CalendarEvent> proposals) {
    }

    /** 對話裡給人看的一句：誰說的（使用者或助理）、說了什麼。 */
    public record Line(boolean fromUser, String text) {
    }

    /**
     * 這段對話給人看的部分：使用者的話、助理的回覆，照順序。重新整理頁面之後，畫面靠它接回同一段對話。
     *
     * 記憶是給模型看的格式，裡面有工具呼叫和工具結果；這裡全部濾掉 —— 那是助理怎麼做到的，不是它說了什麼。
     * 提議過的卡片也不回：卡片後來是加入了還是取消了，記憶裡沒有，顯示一張可能已經過時的卡片不如不顯示。
     * 沒有這段對話（從來沒有、或已經清空）就是空的。
     */
    public List<Line> history(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId 不可為 null");
        try {
            return memory.findByConversationId(conversationId).stream()
                    .filter(m -> m.getMessageType() == MessageType.USER
                            || (m instanceof AssistantMessage a && !a.hasToolCalls() && a.getText() != null && !a.getText().isBlank()))
                    .map(m -> new Line(m.getMessageType() == MessageType.USER, m.getText().strip()))
                    .toList();
        } catch (RuntimeException e) {
            throw new IllegalStateException("讀取 AI 助理的對話失敗：" + e.getMessage(), e);
        }
    }

    /** 清空這段對話（刪掉記憶）。本來就沒有也不算錯：使用者要的結果本來就是「它不在」。 */
    public void forget(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId 不可為 null");
        try {
            memory.deleteByConversationId(conversationId);
        } catch (RuntimeException e) {
            throw new IllegalStateException("清空 AI 助理的對話失敗：" + e.getMessage(), e);
        }
    }

    /**
     * 回應使用者的一句話（agent loop）。conversationId 是這段對話的身分（UUID），同一段對話的每一句都帶同一個。
     *
     * <pre>
     * 讀出歷史（含工具呼叫）＋ 這一句
     * 重複最多 MAX_STEPS 步：
     *     呼叫模型（帶著工具定義；Spring AI 2.0 的 ChatModel 只會回「要呼叫哪個工具」，不會自己執行）
     *     沒要工具 → 這就是回覆，結束
     *     要工具   → ToolCallingManager 執行，「呼叫」和「結果」都接到對話後面，再問一次
     * 存回記憶（只留最近 MAX_TURNS 輪）
     * </pre>
     *
     * 失敗時（模型、資料庫出事、繞圈超過 MAX_STEPS）一律丟 IllegalStateException：
     * 背後的例外型別五花八門，裡面還可能混著 IllegalArgumentException，不包起來會被誤認成「呼叫端送錯」。
     */
    public Reply reply(String conversationId, String message) {
        Objects.requireNonNull(conversationId, "conversationId 不可為 null");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("訊息不可為 null 或空白");
        }
        try {
            // 每一輪都重給 system：日期表要跟著「現在」走，昨天開始的對話今天繼續，「明天」就不一樣了。所以 system 不進記憶
            SystemMessage system = new SystemMessage(instruction(LocalDateTime.now(clock)));
            List<Message> conversation = new ArrayList<>(memory.findByConversationId(conversationId));
            conversation.add(new UserMessage(message));
            // 工具要知道使用者實際說過什麼（例如有沒有講早上晚上），不能只看模型整理出來的那句
            List<String> userSaid = conversation.stream()
                    .filter(m -> m.getMessageType() == MessageType.USER).map(Message::getText).toList();
            CalendarTools tools = new CalendarTools(parseEvents, listEvents, findFreeSlots, userSaid);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(TEMPERATURE)
                    .maxTokens(MAX_TOKENS)
                    .toolCallbacks(ToolCallbacks.from(tools))
                    .build();

            // 抓到說謊時暫時加進請求的兩則（那句假話 + 提醒）；不存進記憶，也不留在之後的對話裡
            List<Message> correction = List.of();
            for (int step = 0; step < MAX_STEPS; step++) {
                Prompt prompt = new Prompt(withSystem(system, concat(conversation, correction)), options);
                ChatResponse response = chatModel.call(prompt);
                if (!response.hasToolCalls()) {
                    AssistantMessage answer = response.getResult().getOutput();
                    String text = answer.getText();
                    if (text == null || text.isBlank()) {
                        // 不存：空白的回覆留在記憶裡，下一輪模型會照著學
                        throw new IllegalStateException("AI 助理沒有回任何話");
                    }
                    // 這一輪查過行事曆就不檢查：使用者問「剛剛那個加了嗎」，查到了照實說「已加入」是對的
                    if (tools.proposals().isEmpty() && !tools.checkedCalendar() && CLAIMS_ACTION.matcher(text).find()) {
                        if (correction.isEmpty()) {
                            log.warn("AI 助理沒呼叫工具卻說有卡片或已加入，提醒它重來：conversation={} reply={}", conversationId, text);
                            correction = List.of(answer, new UserMessage(CORRECTION));
                            continue;
                        }
                        log.warn("AI 助理提醒過還是說有卡片或已加入，改成固定回覆：conversation={} reply={}", conversationId, text);
                        answer = new AssistantMessage(HONEST_FALLBACK);
                        text = HONEST_FALLBACK;
                    }
                    conversation.add(answer);
                    memory.saveAll(conversationId, recentTurns(conversation));
                    log.info("AI 助理回覆：conversation={} steps={} proposals={} reply={}",
                            conversationId, step + 1, tools.proposals().size(), text);
                    return new Reply(text.strip(), tools.proposals());
                }
                for (AssistantMessage.ToolCall call : response.getResult().getOutput().getToolCalls()) {
                    log.info("AI 助理呼叫工具：conversation={} tool={} arguments={}", conversationId, call.name(), call.arguments());
                }
                // 執行工具；回來的歷史 = 這次送出的訊息 + 助理的工具呼叫 + 工具結果。
                // system 和提醒那兩則都拿掉：提醒只對這一次有用，留在記憶裡會讓下一輪的模型看到一句假話
                ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
                conversation = withoutSystem(result.conversationHistory(), correction);
                correction = List.of();
            }
            throw new IllegalStateException("AI 助理呼叫工具超過 " + MAX_STEPS + " 次還沒回話");
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("AI 助理失敗：" + e.getMessage(), e);
        }
    }

    /**
     * 只留最近 MAX_TURNS 輪。從某一輪的開頭（使用者的話）截，不從中間截：
     * 工具結果前面一定要有呼叫它的那則助理訊息，拆開了 API 會拒收整個請求。
     */
    static List<Message> recentTurns(List<Message> conversation) {
        List<Integer> turnStarts = new ArrayList<>();
        for (int i = 0; i < conversation.size(); i++) {
            if (conversation.get(i).getMessageType() == MessageType.USER) {
                turnStarts.add(i);
            }
        }
        if (turnStarts.size() <= MAX_TURNS) {
            return List.copyOf(conversation);
        }
        return List.copyOf(conversation.subList(turnStarts.get(turnStarts.size() - MAX_TURNS), conversation.size()));
    }

    private static List<Message> withSystem(SystemMessage system, List<Message> conversation) {
        List<Message> messages = new ArrayList<>(conversation.size() + 1);
        messages.add(system);
        messages.addAll(conversation);
        return messages;
    }

    // 拿掉 system，以及這一次暫時加進去的提醒（用同一個物件比對：ToolCallingManager 回來的歷史裡就是我們放進去的那幾個）
    private static List<Message> withoutSystem(List<Message> messages, List<Message> temporary) {
        Set<Message> skip = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        skip.addAll(temporary);
        return new ArrayList<>(messages.stream()
                .filter(m -> m.getMessageType() != MessageType.SYSTEM && !skip.contains(m))
                .toList());
    }

    private static List<Message> concat(List<Message> first, List<Message> second) {
        List<Message> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /**
     * 給模型的指示。每一段都是實測出來的：
     * - 「不用算日期和時間」：讓它自己填時間時，「下週三」會算成星期二、結束時間會自己編
     * - 對話範例：只用文字說「不確定就問」，它照樣直接提議 07:00；給了一段範例對話，它才會先問、
     *   使用者回「晚上」之後再把整句合起來提議。找空檔的範例則是為了「to 不含，所以要多一天」——
     *   沒有範例時它查「這週三、四」只查到週三
     * - 「不能自己存、不要說已加入」：存檔一定要使用者按確認，模型的話不能讓人以為已經存了
     * - 「一定要呼叫 propose_events，不要只用文字複述」：經過 Spring AI 之後，「明天七點吃晚餐」它只回了
     *   「明天晚上七點吃晚餐。」—— 判斷對了（晚上），卻沒有提議，畫面上就沒有卡片可以確認
     * - 「包括改時間」：使用者說「其實是早上」也是要新增（一張新的卡片），不是聊天
     * - 「你沒辦法加入行事曆」「沒呼叫就不要說已顯示卡片」：實際發生過它沒呼叫工具卻說「已顯示卡片」「已加入」。
     *   這句只是提醒，真正擋住說謊的是 reply 裡的 CLAIMS_ACTION 檢查
     * - 「提議過的行程可能加入了也可能取消了，先用 list_events 查」：按「加入行事曆」走的是 POST /events，
     *   按「取消」只改前端的畫面，兩個都不經過助理，所以助理永遠不知道卡片後來怎麼了。
     *   不另外記卡片的狀態：行事曆本身就是事實來源 —— 加入的查得到、取消的查不到。
     *   只有規則時，同一句「剛剛那個有加進去嗎？」有時會查、有時回「我無法確認」，加了範例才會穩定地查
     * - 範例裡的日期是真的日期（每天代入）：範例寫「from = 這週三」時，模型會照抄，把「這週三」當參數傳給工具
     *   （log 裡看得到），範例一多就抄得更兇，連找空檔都壞了
     * - 「只有帶著時段的字才不用問」：原本寫成「從要做的事看得出來就不用問」，模型把「七點吃飯」也當成看得出來、
     *   直接提議 19:00 —— 但早上七點吃飯也很常見。界線改成看字：早餐、晚餐、晨跑這種字本身帶著時段，其他一律問
     */
    static String instruction(LocalDateTime now) {
        return """
                你是使用者的行事曆助理，用繁體中文、簡短地回話。
                現在是 %s。日期一律從下表找對應的那一列，不要自己推算：
                %s

                你能做的事：
                - 使用者要新增行程（包括改時間，例如「其實是早上」）：用 propose_events，把行程整理成一句完整的話傳進去（你不用算日期和時間，行事曆會解析）。畫面會顯示卡片讓使用者確認。你沒辦法把行程加入行事曆，只有使用者按卡片上的「加入行事曆」才會加入，所以不要說「已加入」；沒呼叫 propose_events 就不要說「已顯示卡片」。
                  使用者講了要做的事和時間，就是要新增行程：一定要呼叫 propose_events，不要只用文字把那句話複述一遍（時間不確定時才先問）。
                - 使用者問某天或某段時間有什麼行程：用 list_events 查，再用一兩句話回答。
                  之前提議過的行程，使用者可能加入了、也可能取消了，你不知道是哪一個：問到它有沒有加入、或要根據它回答時，先用 list_events 查，行事曆上查得到才算已加入。
                - 使用者問什麼時候有空：用 find_free_slots 查，再回答。
                時間不確定時先問，不要猜：沒講上午下午的 7～11 點，就問「早上還是晚上？」。
                只有要做的事本身就帶著時段的字才不用問：早餐、午餐、晚餐、宵夜、晨跑、夜跑、夜唱（「七點吃晚餐」是晚上、「七點晨跑」是早上）。
                「吃飯」「見面」「開會」「運動」「看電影」這種早上晚上都可能的，一定要問，不要自己決定。
                沒講上午下午的 1～6 點一律當下午，不用問。

                範例：
                使用者：明天七點和 Amy 見面
                助理（不呼叫工具，直接問）：早上七點還是晚上七點？
                使用者：晚上
                助理：呼叫 propose_events，description =「明天晚上七點和 Amy 見面」
                —
                使用者：剛剛那個有加進去嗎？（剛剛提議的是明天的行程）
                助理：呼叫 list_events，from = %s，to = %s。查得到就說已經在行事曆上，查不到就說還沒有、要的話按卡片上的「加入行事曆」
                —
                使用者：明天、後天哪個下午有空？
                助理：呼叫 find_free_slots，from = %s，to = %s（to 不含，所以要多一天），period = AFTERNOON""".formatted(
                now.format(NOW_FORMAT), DateTable.of(now.toLocalDate()),
                now.toLocalDate().plusDays(1), now.toLocalDate().plusDays(2),
                now.toLocalDate().plusDays(1), now.toLocalDate().plusDays(3));
    }
}
