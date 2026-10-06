package eat.calendar.adapter.in.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.calendar.adapter.out.extraction.springai.SpringAiExtractEventsAdapter;
import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.domain.service.FindFreeSlotsService;
import eat.calendar.application.domain.service.ListEventsService;
import eat.calendar.application.domain.service.ParseEventsService;
import eat.calendar.application.port.out.LoadEventsPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * AI 助理對真的 llama-server 跑一段一段的對話：模型會不會反問、選不選得對工具、整理出的句子解析出來對不對。
 *
 * 情境都是開發時實測過的那幾種。溫度 0，同一段對話每次走同一條路，所以可以斷言得很死：
 * - 反問那一題：沒有提議、回覆裡有「晚上」；接著回「晚上」才提議 19:00
 * - 查詢、找空檔：回覆裡提到假資料裡的那幾筆
 * 回覆的措辭不斷言整句（換一版模型就會變），只看關鍵字。換了模型或改了 prompt，先跑這個。
 *
 * 除了模型，其他都是真的：抽取 adapter、ParseEvents／FindFreeSlots 的 service、DateTable、SaidTimes。
 * 行事曆資料是假的（固定兩筆），對話記憶放記憶體 —— 這裡不測資料庫。
 */
@DisplayName("CalendarAssistant（真實 server 的對話）")
class CalendarAssistantConversationTest {

    private static final URI BASE_URI =
            URI.create(System.getProperty("spring.ai.openai.chat.base-url", "http://127.0.0.1:8080/v1"));

    // 台北 2026-10-06（週二）11:30
    private static final Clock TUE = Clock.fixed(Instant.parse("2026-10-06T03:30:00Z"), ZoneId.of("Asia/Taipei"));

    // 明天（週三）的兩筆：上午的週會、晚上的瑜珈
    private static final LoadEventsPort CALENDAR = range -> List.of(
            ScheduledEvent.schedule(new CalendarEvent("產品週會", LocalDateTime.of(2026, 10, 7, 9, 0), LocalDateTime.of(2026, 10, 7, 10, 0))),
            ScheduledEvent.schedule(new CalendarEvent("瑜珈課", LocalDateTime.of(2026, 10, 7, 18, 0), LocalDateTime.of(2026, 10, 7, 19, 0))));

    private CalendarAssistant assistant;
    private String conversation;

    @BeforeAll
    static void requireRunningServer() {
        Assumptions.assumeTrue(isHealthy(), () -> "llama-server 沒有在 " + BASE_URI + " 執行，跳過整合測試");
    }

    private static boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URI.toString().replaceFirst("/v1/?$", "") + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @BeforeEach
    void newConversation() {
        // 設定跟 application.properties 的 spring.ai.openai.chat.* 一致
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(BASE_URI.toString())
                        .apiKey("not-used-by-llama-server")
                        .model("qwen3-8b")
                        .timeout(Duration.ofMinutes(2))
                        .maxRetries(0)
                        .build())
                .build();
        assistant = new CalendarAssistant(chatModel, new InMemoryChatMemoryRepository(),
                new ParseEventsService(new SpringAiExtractEventsAdapter(chatModel), TUE),
                new ListEventsService(CALENDAR), new FindFreeSlotsService(CALENDAR, TUE), TUE);
        conversation = UUID.randomUUID().toString();
    }

    private CalendarAssistant.Reply say(String message) {
        return assistant.reply(conversation, message);
    }

    @Test
    @DisplayName("講清楚的新增：直接提議，時間經過既有流程核對（沒講結束 → 一小時）")
    void clearRequestIsProposed() {
        List<CalendarEvent> proposals = say("明天下午3點和 Amy 開會").proposals();

        assertEquals(1, proposals.size());
        assertEquals(LocalDateTime.of(2026, 10, 7, 15, 0), proposals.getFirst().start());
        assertEquals(LocalDateTime.of(2026, 10, 7, 16, 0), proposals.getFirst().end());
    }

    @Test
    @DisplayName("從活動看得出是晚上（七點吃晚餐）：不問，直接提議 19:00")
    void activityTellsTheTime() {
        List<CalendarEvent> proposals = say("明天七點吃晚餐").proposals();

        assertEquals(1, proposals.size(), "應該提議而不是只用文字複述");
        assertEquals(LocalDateTime.of(2026, 10, 7, 19, 0), proposals.getFirst().start());
    }

    @Test
    @DisplayName("真的不確定（七點和 Amy 見面）：先反問、不提議；回「晚上」之後才提議 19:00")
    void asksWhenUnsure() {
        CalendarAssistant.Reply question = say("明天七點和 Amy 見面");

        assertTrue(question.proposals().isEmpty(), "不確定時不該先提議：" + question.proposals());
        assertTrue(question.text().contains("晚上"), question.text());

        List<CalendarEvent> proposals = say("晚上").proposals();

        assertEquals(1, proposals.size());
        assertEquals(LocalDateTime.of(2026, 10, 7, 19, 0), proposals.getFirst().start());
    }

    @Test
    @DisplayName("一句兩筆：兩張卡片，「下週三」查表查對，第一筆不會拿到第二筆的結束時間")
    void twoEventsInOneSentence() {
        List<CalendarEvent> proposals = say("明天三點跟小明吃飯，下週三早上九點到十點半看牙醫").proposals();

        assertEquals(2, proposals.size());
        assertEquals(LocalDateTime.of(2026, 10, 7, 15, 0), proposals.get(0).start());
        assertEquals(LocalDateTime.of(2026, 10, 7, 16, 0), proposals.get(0).end());
        assertEquals(LocalDateTime.of(2026, 10, 14, 9, 0), proposals.get(1).start());
        assertEquals(LocalDateTime.of(2026, 10, 14, 10, 30), proposals.get(1).end());
    }

    @Test
    @DisplayName("查詢：查出明天的兩筆，不提議任何東西")
    void listsEvents() {
        CalendarAssistant.Reply reply = say("明天有什麼行程？");

        assertTrue(reply.proposals().isEmpty());
        assertTrue(reply.text().contains("產品週會") && reply.text().contains("瑜珈"), reply.text());
    }

    @Test
    @DisplayName("找空檔：下午是 12–18（由程式決定），避開行程")
    void findsFreeSlots() {
        CalendarAssistant.Reply reply = say("明天下午有空嗎？");

        assertTrue(reply.proposals().isEmpty());
        // 只看數字不看寫法：模型有時寫「12:00–18:00」、有時寫「12 點到 18 點」，兩種都對
        assertTrue(reply.text().contains("12") && reply.text().contains("18"), reply.text());
    }

    @Test
    @DisplayName("閒聊：不呼叫工具、不提議")
    void smallTalk() {
        assertTrue(say("今天天氣真好").proposals().isEmpty());
    }

    @Test
    @DisplayName("回報的 bug：提議過晚上之後說「其實是早上」→ 真的再提議一次 07:00（不是只說「已顯示卡片」）")
    void correctionIsProposedAgain() {
        say("明天七點吃飯");
        say("晚上");

        List<CalendarEvent> proposals = say("其實是早上").proposals();

        assertEquals(1, proposals.size(), "改時間應該呼叫 propose_events，畫面上才有卡片");
        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 0), proposals.getFirst().start());
    }

    @Test
    @DisplayName("使用者回報：「七點吃飯」「八點開會」「七點運動」看不出早晚 → 都要先問，不能自己決定")
    void genericActivitiesAreAsked() {
        for (String said : List.of("明天七點吃飯", "明天八點開會", "明天七點要運動")) {
            newConversation();
            CalendarAssistant.Reply reply = say(said);

            assertTrue(reply.proposals().isEmpty(), said + " 不該直接提議：" + reply.proposals());
            assertTrue(reply.text().contains("晚上") || reply.text().contains("早上"), said + " → " + reply.text());
        }
    }

    @Test
    @DisplayName("活動本身帶著時段（晨跑、宵夜）：不問，直接提議")
    void periodWordsAreInferred() {
        assertEquals(LocalDateTime.of(2026, 10, 7, 7, 0), say("明天七點晨跑").proposals().getFirst().start());
        newConversation();
        assertEquals(LocalDateTime.of(2026, 10, 7, 23, 0), say("明天十一點吃宵夜").proposals().getFirst().start());
    }
}

