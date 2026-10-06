package eat.calendar.adapter.in.assistant;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.Category;
import eat.calendar.application.domain.model.DayPeriod;
import eat.calendar.application.domain.model.FreeSlot;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.port.in.FindFreeSlotsUseCase;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.ParseEventsUseCase;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 助理能用的工具：模型決定要不要呼叫、帶什麼參數，Spring AI 把呼叫轉到這裡的方法。
 *
 * 在架構上，這個類別是 inbound adapter —— 跟 CalendarController 同一個角色，只是呼叫端從瀏覽器換成了模型。
 * 它只認得 use case（port.in），不認得資料庫、不認得抽取的實作；規則一條都不在這裡。
 *
 * 每個請求建一份新的：propose_events 提議的行程收在這裡，請求結束後交給畫面顯示成卡片。
 * 提議不等於存檔 —— 存檔一定是使用者在畫面上按確認（走原本的 POST /events）。
 *
 * 工具回給模型的都是給人看得懂的短文字，而不是 JSON：模型接著要用一兩句話轉述給使用者，
 * 已經整理好的文字比較不會被它轉述錯。
 */
public final class CalendarTools {

    private final ParseEventsUseCase parseEvents;
    private final ListEventsUseCase listEvents;
    private final FindFreeSlotsUseCase findFreeSlots;
    private final String userSaid;
    private final List<CalendarEvent> proposals = new ArrayList<>();
    private boolean checkedCalendar;

    // 使用者講過的話裡，表示時段的寫法：早上、上午、中午、下午、晚上、傍晚、清晨、凌晨、半夜、宵夜、早餐、晚餐、晨跑……
    // 都至少有其中一個字；或是 AM/PM；或是 13～23 點的 24 小時制（「19點」「19:00」）
    private static final Pattern SAID_A_PERIOD =
            Pattern.compile("[早晚午晨夜凌]|(?i:a\\.?m\\.?|p\\.?m\\.?)|(?<!\\d)(1[3-9]|2[0-3])\\s*[點:：]");

    /**
     * @param userSaid 這段對話裡使用者說過的所有話（包括這一句）：propose_events 要拿它核對「使用者有沒有講早上還是晚上」
     */
    public CalendarTools(ParseEventsUseCase parseEvents, ListEventsUseCase listEvents, FindFreeSlotsUseCase findFreeSlots,
                         List<String> userSaid) {
        this.parseEvents = Objects.requireNonNull(parseEvents, "parseEvents 不可為 null");
        this.listEvents = Objects.requireNonNull(listEvents, "listEvents 不可為 null");
        this.findFreeSlots = Objects.requireNonNull(findFreeSlots, "findFreeSlots 不可為 null");
        this.userSaid = String.join("\n", Objects.requireNonNull(userSaid, "userSaid 不可為 null"));
    }

    /** 這一輪對話裡提議的行程（照提議的順序），給畫面顯示成卡片。 */
    public List<CalendarEvent> proposals() {
        return List.copyOf(proposals);
    }

    /**
     * 只收一句話，不收時間：時間交給已經驗證過的抽取流程解析（日期對照表、溫度 0、SaidTimes 核對、domain 規則）。
     * 實測讓模型自己填時間時，它會把「下週三」算成星期二、自己編結束時間；
     * 讓它把對話整理成一句完整的話（「明天晚上七點和 Amy 見面」），它做得很穩。
     */
    @Tool(name = "propose_events", description = "把使用者要新增的行程交給行事曆解析，畫面會顯示卡片讓使用者確認（不會存檔）。")
    public String proposeEvents(
            @ToolParam(description = "一句完整的中文：日期、幾點（含上午下午）、要做的事、地點；對話裡補充過的資訊要合併進去，例如「明天晚上七點和 Amy 見面」")
            String description) {
        List<CalendarEvent> parsed;
        try {
            parsed = parseEvents.parseEvents(description);
        } catch (IllegalArgumentException e) {
            return "沒有收到行程的描述，請把要新增的行程寫成一句話再呼叫。";
        }
        if (parsed.isEmpty()) {
            return "這句話裡解析不出行程。請向使用者確認日期和時間。";
        }
        if (parsed.stream().anyMatch(this::needsMorningOrEvening)) {
            return "沒有提議：使用者沒講是早上還是晚上（例如七點可能是 07:00 也可能是 19:00）。"
                    + "請先問使用者「早上還是晚上？」，等他回答再呼叫 propose_events。";
        }
        proposals.addAll(parsed);
        return "已顯示 " + parsed.size() + " 張卡片，等使用者確認：\n" + lines(parsed.stream().map(CalendarTools::describe).toList());
    }

    /**
     * 這一輪有沒有成功查過行事曆（list_events）。查過之後說「已經加入了」可能是真的（提議過的卡片使用者按了加入），
     * 所以 CalendarAssistant 的說謊檢查要知道這件事。
     */
    public boolean checkedCalendar() {
        return checkedCalendar;
    }

    /**
     * 這個行程是 7～11 點或 19～23 點（早上晚上都可能），而使用者從頭到尾沒講過任何時段的字？那就不該替他決定。
     *
     * 為什麼由程式擋、不靠 prompt：prompt 已經寫明「吃飯、見面、開會看不出早晚，一定要問」，
     * 模型對「明天七點吃飯」仍然自己補上「晚上」直接提議 19:00（溫度 0，每次都一樣）。
     * 核對的是使用者實際說過的話，不是模型整理出來的那句 —— 模型補的「晚上」不算數。
     * 「七點吃晚餐」「七點晨跑」「十一點吃宵夜」本身就有晚、晨、夜，照樣直接提議。
     *
     * 1～6 點不在這裡：沒講上下午的 1～6 點一律當下午（SaidTimes），不必問；12～18 點本來就不會被誤會。
     * 代價：一句話兩個行程、其中一個有講時段，另一個也會被當成講過（這裡看的是整段對話，不是每一筆）。
     */
    private boolean needsMorningOrEvening(CalendarEvent event) {
        int hour = event.start().getHour();
        boolean couldBeEither = (hour >= 7 && hour <= 11) || (hour >= 19 && hour <= 23);
        return couldBeEither && !SAID_A_PERIOD.matcher(userSaid).find();
    }

    @Tool(name = "list_events", description = "查詢一段日期裡的行程。")
    public String listEvents(
            @ToolParam(description = "開始日期（含），格式 yyyy-MM-dd") String from,
            @ToolParam(description = "結束日期（不含），格式 yyyy-MM-dd；只查一天就填隔天") String to) {
        try {
            List<ScheduledEvent> events = listEvents.listEvents(LocalDate.parse(from), LocalDate.parse(to));
            checkedCalendar = true;
            if (events.isEmpty()) {
                return from + " 到 " + to + "（不含）之間沒有行程。";
            }
            return lines(events.stream().map(e -> describe(e.event())).toList());
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return "日期不對（" + e.getMessage() + "）。日期格式是 yyyy-MM-dd，而且 to 要晚於 from。";
        }
    }

    @Tool(name = "find_free_slots", description = "找一段日期裡、某個時段中沒有行程的空檔。已經過去的時間不算。")
    public String findFreeSlots(
            @ToolParam(description = "開始日期（含），格式 yyyy-MM-dd") String from,
            @ToolParam(description = "結束日期（不含），格式 yyyy-MM-dd；只查一天就填隔天") String to,
            @ToolParam(description = "時段：MORNING 早上 08–12、AFTERNOON 下午 12–18、EVENING 晚上 18–22、ANYTIME 不限 08–22") DayPeriod period,
            @ToolParam(description = "至少要幾分鐘才算空檔，沒講就填 60") Integer minMinutes) {
        try {
            Duration minimum = Duration.ofMinutes(minMinutes == null ? 60 : minMinutes);
            List<FreeSlot> slots = findFreeSlots.findFreeSlots(LocalDate.parse(from), LocalDate.parse(to),
                    period == null ? DayPeriod.ANYTIME : period, minimum);
            if (slots.isEmpty()) {
                return "這段期間的這個時段沒有空檔。";
            }
            return lines(slots.stream().map(CalendarTools::describe).toList());
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return "參數不對（" + e.getMessage() + "）。日期格式是 yyyy-MM-dd、to 要晚於 from、分鐘數要是正的。";
        }
    }

    // 「2026-10-07（週三）15:00–16:00 跟小明吃飯〔社交〕＠拉麵店」
    static String describe(CalendarEvent event) {
        String location = event.location().map(l -> "＠" + l).orElse("");
        return when(event.start(), event.end()) + " " + event.title() + "〔" + label(event.category()) + "〕" + location;
    }

    // 「2026-10-07（週三）13:00–15:00（2 小時）」
    static String describe(FreeSlot slot) {
        long minutes = slot.length().toMinutes();
        String length = minutes % 60 == 0 ? (minutes / 60) + " 小時" : minutes + " 分鐘";
        return when(slot.start(), slot.end()) + "（" + length + "）";
    }

    private static String when(LocalDateTime start, LocalDateTime end) {
        String endText = end.toLocalDate().equals(start.toLocalDate())
                ? end.toLocalTime().toString()
                : end.toLocalDate() + " " + end.toLocalTime();
        return start.toLocalDate() + "（週" + "一二三四五六日".charAt(start.getDayOfWeek().getValue() - 1) + "）"
                + start.toLocalTime() + "–" + endText;
    }

    private static String label(Category category) {
        return switch (category) {
            case WORK -> "工作";
            case PERSONAL -> "個人";
            case HEALTH -> "健康";
            case SOCIAL -> "社交";
        };
    }

    private static String lines(List<String> lines) {
        return lines.stream().map(line -> "- " + line).collect(Collectors.joining("\n"));
    }
}
