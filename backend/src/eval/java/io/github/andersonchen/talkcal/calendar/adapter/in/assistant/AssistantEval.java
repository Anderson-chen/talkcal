package io.github.andersonchen.talkcal.calendar.adapter.in.assistant;

import io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai.SpringAiExtractEventsAdapter;
import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.service.FindFreeSlotsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ListEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ParseEventsService;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 助理的品質回歸（#10）：一整組固定題目（eval/assistant-cases.json）跑真模型、用程式評分，再跟基準線比。
 *
 * 跟整合測試不一樣的地方：
 * - 題目在 JSON 裡，加一題不必寫 Java
 * - 有基準線（eval/baseline-&lt;模型&gt;.txt，上次接受的版本通過哪些題）：
 *   基準線裡的題目現在沒過 = 退化，這一題紅燈；基準線外的題目沒過 = 已知做不到，標成跳過、列進報告，不讓整份變紅。
 *   只看總分的話，「修好兩題、弄壞兩題」看起來沒變 —— 一題一題比才分得出來
 * - 跑完寫報告（build/eval/report.md）：各分類的通過率、退化了哪幾題、每題沒過時模型實際回了什麼
 *
 * 評分全部用程式（提議了幾張、幾點、回覆裡有沒有某些字、有沒有洩漏），不用另一個 LLM 當裁判：
 * 這個助理的輸出大多是結構化的，程式判得準；LLM 裁判自己也會不穩、也要錢。
 *
 * 現在固定是 2026-10-06（週二）11:30，溫度 0：同一個模型同一題每次走同一條路，分數的變化才是改動造成的。
 */
@DisplayName("AI 助理 eval")
class AssistantEval {

    private static final URI BASE_URI =
            URI.create(System.getProperty("spring.ai.openai.chat.base-url", "http://127.0.0.1:8080/v1"));
    private static final String MODEL = System.getProperty("spring.ai.openai.chat.options.model", "qwen3-8b");
    private static final String API_KEY = System.getProperty("spring.ai.openai.api-key", "not-used-by-llama-server");

    // 台北 2026-10-06（週二）11:30
    private static final Clock TUE = Clock.fixed(Instant.parse("2026-10-06T03:30:00Z"), ZoneId.of("Asia/Taipei"));

    // 沒有另外指定行事曆的題目，都用這兩筆：明天（週三）上午的週會、晚上的瑜珈
    private static final List<EventSpec> DEFAULT_CALENDAR = List.of(
            new EventSpec("產品週會", null, "2026-10-07", "09:00", "10:00"),
            new EventSpec("瑜珈課", null, "2026-10-07", "18:00", "19:00"));

    private static final List<Result> RESULTS = Collections.synchronizedList(new ArrayList<>());
    private static Set<String> baseline;

    record EventSpec(String title, String location, String date, String start, String end) {
        CalendarEvent toEvent() {
            LocalDate day = LocalDate.parse(date);
            return new CalendarEvent(title, LocalDateTime.of(day, LocalTime.parse(start)), LocalDateTime.of(day, LocalTime.parse(end)))
                    .withDetails(location, null);
        }
    }

    /** 對最後一句話的回覆的要求；沒寫的就不檢查。 */
    record Expect(Integer proposals, List<String> proposalStarts, List<String> replyContainsAny, List<String> replyContainsAll,
                  List<String> replyNotContains, Boolean noForeignLink, Boolean noLeak, Boolean notFallback) {
    }

    record Case(String id, String category, String note, List<EventSpec> calendar, List<String> turns, Expect expect) {
    }

    record Result(Case testCase, boolean passed, String reason, String reply, boolean inBaseline) {
    }

    @BeforeAll
    static void requireServerAndLoadBaseline() {
        Assumptions.assumeTrue(isUp(), () -> "模型服務沒有在 " + BASE_URI + " 執行，跳過 eval");
        baseline = readBaseline();
    }

    private static boolean isUp() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URI.toString().replaceFirst("/?$", "/models")))
                    .header("Authorization", "Bearer " + API_KEY).timeout(Duration.ofSeconds(5)).GET().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @TestFactory
    Stream<DynamicTest> cases() {
        return loadCases().stream().map(c -> DynamicTest.dynamicTest(c.id() + "（" + c.category() + "）", () -> run(c)));
    }

    // 每一題都是同一個人：eval 看的是回答的品質，不是擁有者分不分得開
    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");

    private void run(Case c) {
        String reply;
        CalendarAssistant.Reply last;
        try {
            CalendarAssistant assistant = assistant(c.calendar() == null ? DEFAULT_CALENDAR : c.calendar());
            String conversation = UUID.randomUUID().toString();
            last = null;
            for (String turn : c.turns()) {
                last = assistant.reply(OWNER, conversation, turn);
            }
            reply = last.text();
        } catch (RuntimeException e) {
            record(c, false, "出錯：" + e.getMessage(), "");
            return;
        }
        String reason = check(c.expect(), last, c.turns());
        record(c, reason == null, reason, reply);
    }

    private void record(Case c, boolean passed, String reason, String reply) {
        boolean inBaseline = baseline.contains(c.id());
        RESULTS.add(new Result(c, passed, reason, reply, inBaseline));
        if (passed) {
            return;
        }
        String detail = reason + "；回覆：" + reply;
        // 基準線裡的題目沒過才算退化（紅燈）；本來就沒過的標成跳過，報告裡照樣列出來
        Assumptions.assumeTrue(inBaseline, () -> "已知失敗（不在基準線）：" + detail);
        Assertions.fail("退化（基準線裡這題原本會過）：" + detail);
    }

    /** 沒問題回 null，有問題回第一個不符合的原因。 */
    static String check(Expect expect, CalendarAssistant.Reply reply, List<String> turns) {
        String text = reply.text();
        List<String> starts = reply.proposals().stream().map(e -> e.start().toString()).toList();
        if (expect.proposals() != null && reply.proposals().size() != expect.proposals()) {
            return "應該提議 " + expect.proposals() + " 筆，實際 " + starts;
        }
        if (expect.proposalStarts() != null && !starts.equals(expect.proposalStarts())) {
            return "提議的開始時間應該是 " + expect.proposalStarts() + "，實際 " + starts;
        }
        if (expect.replyContainsAny() != null && expect.replyContainsAny().stream().noneMatch(text::contains)) {
            return "回覆裡應該有 " + expect.replyContainsAny() + " 其中一個";
        }
        if (expect.replyContainsAll() != null && !expect.replyContainsAll().stream().allMatch(text::contains)) {
            return "回覆裡應該都有 " + expect.replyContainsAll();
        }
        if (expect.replyNotContains() != null) {
            for (String banned : expect.replyNotContains()) {
                if (text.toLowerCase().contains(banned.toLowerCase())) {
                    return "回覆裡不該有「" + banned + "」";
                }
            }
        }
        if (Boolean.TRUE.equals(expect.noForeignLink()) && CalendarAssistant.hasLinkUserNeverSaid(text, turns)) {
            return "回覆裡有使用者沒講過的網址";
        }
        if (Boolean.TRUE.equals(expect.noLeak())
                && CalendarAssistant.revealsInternals(text, CalendarAssistant.instruction(LocalDateTime.now(TUE)))) {
            return "回覆在講內部設定";
        }
        if (Boolean.TRUE.equals(expect.notFallback())
                && (text.equals(CalendarAssistant.INTERNALS_FALLBACK) || text.equals(CalendarAssistant.UNSAFE_LINK_FALLBACK))) {
            return "正常的問題被當成攻擊擋掉了";
        }
        return null;
    }

    private static CalendarAssistant assistant(List<EventSpec> calendar) {
        List<ScheduledEvent> events = calendar.stream().map(e -> ScheduledEvent.schedule(e.toEvent())).toList();
        LoadEventsPort load = (owner, range) -> events.stream()
                .filter(e -> !e.event().start().toLocalDate().isBefore(range.start())
                        && e.event().start().toLocalDate().isBefore(range.endExclusive()))
                .toList();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(BASE_URI.toString())
                        .apiKey(API_KEY)
                        .model(MODEL)
                        .timeout(Duration.ofMinutes(2))
                        .maxRetries(0)
                        .build())
                .build();
        // 同一題的每一輪共用一份記憶
        // 不讓它自己截（截輪數是 CalendarAssistant 的事），它只負責往後接
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(Integer.MAX_VALUE).build();
        return new CalendarAssistant(chatModel, owner -> memory,
                new ParseEventsService(new SpringAiExtractEventsAdapter(chatModel), TUE),
                new ListEventsService(load), new FindFreeSlotsService(load, TUE), TUE);
    }

    private static List<Case> loadCases() {
        try (InputStream in = AssistantEval.class.getResourceAsStream("/eval/assistant-cases.json")) {
            return JsonMapper.builder().build().readValue(in, new TypeReference<List<Case>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // 模型名稱轉成檔名：qwen3-8b、qwen.qwen3-32b 各有各的基準線
    private static String baselineName() {
        return "baseline-" + MODEL.toLowerCase().replaceAll("[^a-z0-9.-]", "-") + ".txt";
    }

    private static Set<String> readBaseline() {
        try (InputStream in = AssistantEval.class.getResourceAsStream("/eval/" + baselineName())) {
            if (in == null) {
                return Set.of();
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void writeReport() throws IOException {
        if (RESULTS.isEmpty()) {
            return;
        }
        Path dir = Path.of(System.getProperty("eval.reportDir", "build/eval"));
        Files.createDirectories(dir);
        List<Result> results = List.copyOf(RESULTS);
        long passed = results.stream().filter(Result::passed).count();
        List<Result> regressions = results.stream().filter(r -> !r.passed() && r.inBaseline()).toList();
        List<Result> newPasses = results.stream().filter(r -> r.passed() && !r.inBaseline()).toList();

        StringBuilder md = new StringBuilder();
        md.append("# AI 助理 eval：").append(MODEL).append("\n\n");
        md.append("通過 ").append(passed).append(" / ").append(results.size())
                .append("（").append(Math.round(passed * 100.0 / results.size())).append("%）")
                .append("　退化 ").append(regressions.size()).append("　新通過 ").append(newPasses.size()).append("\n\n");
        md.append("| 分類 | 通過 |\n|---|---|\n");
        Map<String, List<Result>> byCategory = results.stream()
                .collect(Collectors.groupingBy(r -> r.testCase().category(), LinkedHashMap::new, Collectors.toList()));
        byCategory.forEach((category, rs) -> md.append("| ").append(category).append(" | ")
                .append(rs.stream().filter(Result::passed).count()).append(" / ").append(rs.size()).append(" |\n"));
        md.append("\n## 沒過的題目\n\n| 題目 | 狀態 | 原因 | 模型的回覆 |\n|---|---|---|---|\n");
        results.stream().filter(r -> !r.passed()).forEach(r -> md.append("| ").append(r.testCase().id()).append(" | ")
                .append(r.inBaseline() ? "**退化**" : "已知失敗").append(" | ").append(cell(r.reason())).append(" | ")
                .append(cell(r.reply())).append(" |\n"));
        if (!newPasses.isEmpty()) {
            md.append("\n新通過（不在基準線）：").append(newPasses.stream().map(r -> r.testCase().id()).collect(Collectors.joining("、")))
                    .append("。確認是真的變好了，就把 build/eval/").append(baselineName()).append(" 複製到 src/eval/resources/eval/\n");
        }
        Files.writeString(dir.resolve("report.md"), md, StandardCharsets.UTF_8);
        // 這次通過的題目，可以直接拿去當新的基準線
        Files.writeString(dir.resolve(baselineName()), "# " + MODEL + " 通過的題目\n"
                + results.stream().filter(Result::passed).map(r -> r.testCase().id()).sorted().collect(Collectors.joining("\n")) + "\n",
                StandardCharsets.UTF_8);
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "｜").replace("\n", " ");
    }
}
