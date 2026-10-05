package eat.calendar.adapter.in.web;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.port.in.AddEventsUseCase;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.ParseEventsUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 行事曆的 HTTP 入口（inbound adapter）。
 *
 * 三個端點對應三個 use case，也正好是「先預覽再確認」的完整流程：
 * 1. POST /parse —— 一句話 → 行程草稿（不存）
 * 2. POST /events —— 使用者確認（可能改過）的草稿 → 存起來
 * 3. GET  /events —— 月曆一頁的行程
 *
 * 寫法跟 ChatController 一致（元件掃描、只認得 inbound port、錯誤翻成 HTTP 的語言），
 * 但兩個 controller 互不引用：模組之間不共用類別，連錯誤的 wire format 都各寫一份。
 */
@RestController
@RequestMapping("/api/calendar")
@Tag(name = "calendar", description = "行事曆：用一句話新增行程（先預覽、再確認），再按期間讀出來畫月曆")
public final class CalendarController {

    private static final Logger log = LoggerFactory.getLogger(CalendarController.class);

    private final ParseEventsUseCase parseEvents;
    private final AddEventsUseCase addEvents;
    private final ListEventsUseCase listEvents;

    public CalendarController(ParseEventsUseCase parseEvents, AddEventsUseCase addEvents, ListEventsUseCase listEvents) {
        this.parseEvents = Objects.requireNonNull(parseEvents, "parseEvents 不可為 null");
        this.addEvents = Objects.requireNonNull(addEvents, "addEvents 不可為 null");
        this.listEvents = Objects.requireNonNull(listEvents, "listEvents 不可為 null");
    }

    @PostMapping("/parse")
    @Operation(summary = "把一句話解析成行程草稿（不存檔）",
            description = "一句話裡有幾個行程就回幾筆；一筆都沒有就回空陣列。草稿確認（或修改）後送 POST /api/calendar/events 才會存。")
    @ApiResponse(responseCode = "200", description = "解析出的行程草稿，可能是空的")
    @ApiResponse(responseCode = "400", description = "描述是 null 或空白",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "模型沒回應、出錯，或解析出不合理的行程；稍後重試或換個說法",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public EventsPreview parse(@RequestBody ParseRequest request) {
        List<EventFields> events = parseEvents.parseEvents(request.text()).stream().map(EventFields::of).toList();
        return new EventsPreview(events);
    }

    @PostMapping("/events")
    // 201：這是在建立新資源。回應裡帶著每筆的 id，就是新資源的身分
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "把確認過的行程存進行事曆",
            description = "通常就是 /parse 回來的草稿，使用者可能改過。全部存或全部不存。")
    @ApiResponse(responseCode = "201", description = "存好了，每筆都有了 id")
    @ApiResponse(responseCode = "400", description = "某筆行程不合規（標題空白、缺時間、結束不晚於開始），一筆都沒存",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事，一筆都沒存；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public SavedEvents add(@RequestBody AddEventsRequest request) {
        if (request.events() == null) {
            throw new IllegalArgumentException("events 不可為 null");
        }
        // 「確認時重驗規則」就發生在這一行：每一筆都得先成為 CalendarEvent，
        // 使用者在預覽畫面上改壞的（結束早於開始、清空標題）在這裡就丟 IllegalArgumentException → 400
        List<CalendarEvent> events = request.events().stream().map(CalendarController::toDomain).toList();
        return SavedEvents.of(addEvents.addEvents(events));
    }

    @GetMapping("/events")
    @Operation(summary = "列出一段期間裡的行程（月曆一頁）",
            description = "from 含、to 不含；跟期間有重疊的行程都算（跨夜、跨月的會出現在前後兩頁）。依開始時間排序。")
    @ApiResponse(responseCode = "200", description = "期間裡的行程，可能是空的")
    @ApiResponse(responseCode = "400", description = "期間不合規：缺參數、格式不是 yyyy-MM-dd、to 不晚於 from、超過 100 天",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public SavedEvents list(
            @Parameter(description = "期間開始（含）", example = "2026-09-28")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "期間結束（不含）", example = "2026-11-09")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return SavedEvents.of(listEvents.listEvents(from, to));
    }

    // JSON 裡的 events 陣列可能夾著 null（[{...}, null]）。不先擋的話 toDomain 會 NullPointerException → 500
    private static CalendarEvent toDomain(EventFields fields) {
        if (fields == null) {
            throw new IllegalArgumentException("events 裡不可有 null");
        }
        return fields.toDomain();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CalendarFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("行事曆請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new CalendarFailure(e.getMessage()));
    }

    // 只攔 IllegalStateException，不攔整個 RuntimeException：ChatController 踩過的坑，
    // 攔太寬會把 Spring 自己的 4xx（JSON 格式不對、參數型別不對）誤標成 502
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<CalendarFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("模型或資料庫失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new CalendarFailure(e.getMessage()));
    }

    // ── wire format：只服務 HTTP/JSON，不外流到 core ──────────────────────────
    // 名字刻意不跟 ChatController 的巢狀 record 撞（例如不叫 Request、Failure）：
    // springdoc 用類別的簡單名稱當 schema 名稱，撞名的話文件裡兩邊會互相蓋掉。

    public record ParseRequest(
            @Schema(description = "用自然語言描述的行程，不可空白", example = "明天下午三點跟小明吃飯，下週三早上九點看牙醫")
            String text) {
    }

    /**
     * 一筆行程的欄位，預覽的回應和確認的請求共用同一個形狀：
     * 前端把 /parse 拿到的東西原樣（或改過）送回 /events，不必做任何轉換。
     * 時間是 ISO 格式的牆上時間（台北），不帶時區。
     */
    public record EventFields(
            @Schema(description = "要做的事", example = "跟小明吃飯") String title,
            @Schema(description = "開始時間，台北的牆上時間", example = "2026-10-06T15:00:00") LocalDateTime start,
            @Schema(description = "結束時間，必須晚於開始", example = "2026-10-06T16:00:00") LocalDateTime end) {

        static EventFields of(CalendarEvent event) {
            return new EventFields(event.title(), event.start(), event.end());
        }

        CalendarEvent toDomain() {
            return new CalendarEvent(title, start, end);
        }
    }

    public record EventsPreview(@Schema(description = "解析出的行程草稿，還沒存") List<EventFields> events) {
    }

    public record AddEventsRequest(@Schema(description = "要存的行程，通常是 /parse 的結果（可能改過）") List<EventFields> events) {
    }

    public record SavedEvent(
            @Schema(description = "行程的 ID") String id,
            String title,
            LocalDateTime start,
            LocalDateTime end) {

        static SavedEvent of(ScheduledEvent scheduled) {
            CalendarEvent event = scheduled.event();
            return new SavedEvent(scheduled.id().toString(), event.title(), event.start(), event.end());
        }
    }

    public record SavedEvents(List<SavedEvent> events) {

        static SavedEvents of(List<ScheduledEvent> scheduled) {
            return new SavedEvents(scheduled.stream().map(SavedEvent::of).toList());
        }
    }

    public record CalendarFailure(@Schema(description = "給人看的錯誤原因") String error) {
    }
}
