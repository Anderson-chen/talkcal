package eat.calendar.adapter.in.web;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.Category;
import eat.calendar.application.domain.model.EventId;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.port.in.AddEventsUseCase;
import eat.calendar.application.port.in.EventNotFoundException;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.RemoveEventUseCase;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
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
import java.util.Optional;

/**
 * 行事曆的 HTTP 入口（inbound adapter）。
 *
 * 三個端點對應三個 use case：
 * 1. POST   /events       —— 使用者確認過的助理提議、或手動表單填的行程 → 存起來
 * 2. GET    /events       —— 月曆一頁的行程
 * 3. DELETE /events/{id}  —— 拿掉一個行程
 *
 * 一句話變成行程草稿不在這裡：那是 AI 助理的工作（CalendarAssistantController），
 * 草稿以 proposals 回給前端，使用者確認後才送到 POST /events。
 */
@RestController
@RequestMapping("/api/calendar")
@Tag(name = "calendar", description = "行事曆：存下確認過的行程、按期間讀出來畫月曆、拿掉行程")
public final class CalendarController {

    private static final Logger log = LoggerFactory.getLogger(CalendarController.class);

    private final AddEventsUseCase addEvents;
    private final ListEventsUseCase listEvents;
    private final RemoveEventUseCase removeEvent;

    public CalendarController(AddEventsUseCase addEvents, ListEventsUseCase listEvents, RemoveEventUseCase removeEvent) {
        this.addEvents = Objects.requireNonNull(addEvents, "addEvents 不可為 null");
        this.listEvents = Objects.requireNonNull(listEvents, "listEvents 不可為 null");
        this.removeEvent = Objects.requireNonNull(removeEvent, "removeEvent 不可為 null");
    }

    @PostMapping("/events")
    // 201：這是在建立新資源。回應裡帶著每筆的 id，就是新資源的身分
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "把確認過的行程存進行事曆",
            description = "通常是 AI 助理提議、使用者確認過的行程（可能改過），或手動表單填的。全部存或全部不存。")
    @ApiResponse(responseCode = "201", description = "存好了，每筆都有了 id")
    @ApiResponse(responseCode = "400", description = "某筆行程不合規（標題空白、缺時間、結束不晚於開始、分類不認得），一筆都沒存",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事，一筆都沒存；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public SavedEvents add(@RequestBody AddEventsRequest request) {
        if (request.events() == null) {
            throw new IllegalArgumentException("events 不可為 null");
        }
        // 「確認時重驗規則」就發生在這一行：每一筆都得先成為 CalendarEvent，
        // 使用者在確認卡片上改壞的（結束早於開始、清空標題）在這裡就丟 IllegalArgumentException → 400
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

    @DeleteMapping("/events/{id}")
    // 204：刪掉了，沒有東西要回。比回 200 加一個空物件誠實
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "從行事曆拿掉一個行程")
    @ApiResponse(responseCode = "204", description = "刪掉了")
    @ApiResponse(responseCode = "400", description = "id 不是合法的 UUID",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "404", description = "沒有這個行程（可能已經被刪掉了）；重新整理畫面",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public void remove(@Parameter(description = "行程的 ID") @PathVariable String id) {
        // 收 String 自己轉，不讓 Spring 直接轉成 UUID：格式錯的時候錯誤訊息是 EventId 的那句話，跟其他 400 一致
        removeEvent.removeEvent(EventId.of(id));
    }

    // JSON 裡的 events 陣列可能夾著 null（[{...}, null]）。不先擋的話 toDomain 會 NullPointerException → 500
    private static CalendarEvent toDomain(EventFields fields) {
        if (fields == null) {
            throw new IllegalArgumentException("events 裡不可有 null");
        }
        return fields.toDomain();
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<CalendarFailure> onEventNotFound(EventNotFoundException e) {
        log.warn("行程不存在，回 404：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new CalendarFailure(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CalendarFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("行事曆請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new CalendarFailure(e.getMessage()));
    }

    // 只攔 IllegalStateException，不攔整個 RuntimeException：
    // 攔太寬會把 Spring 自己的 4xx（JSON 格式不對、參數型別不對）誤標成 502
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<CalendarFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("資料庫失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new CalendarFailure(e.getMessage()));
    }

    // ── wire format：只服務 HTTP/JSON，不外流到 core ──────────────────────────
    // 名字都帶著業務的字（CalendarFailure、不叫 Failure）：
    // springdoc 用類別的簡單名稱當 schema 名稱，跟別的 controller 撞名的話文件裡會互相蓋掉。

    /**
     * 一筆行程的欄位，助理的提議（proposals）和確認的請求共用同一個形狀：
     * 前端把助理提議的卡片原樣（或改過）送回 /events，不必做任何轉換。
     * 時間是 ISO 格式的牆上時間（台北），不帶時區。
     *
     * 分類在線路上是字串（"WORK"…），不是直接把 domain 的 Category 放上來：
     * enum 改個名字不該順手改掉 API 契約。對照寫在 toCategory()／fromCategory() 兩個方法裡。
     * 地點、備註沒有就是 null（JSON 裡不帶或寫 null 都行）。
     */
    public record EventFields(
            @Schema(description = "要做的事", example = "跟小明吃飯") String title,
            @Schema(description = "開始時間，台北的牆上時間", example = "2026-10-06T15:00:00") LocalDateTime start,
            @Schema(description = "結束時間，必須晚於開始", example = "2026-10-06T16:00:00") LocalDateTime end,
            @Schema(description = "分類；不帶就是 PERSONAL", allowableValues = {"WORK", "PERSONAL", "HEALTH", "SOCIAL"},
                    example = "SOCIAL", nullable = true) String category,
            @Schema(description = "地點，選填", example = "公司附近的拉麵店", nullable = true) String location,
            @Schema(description = "備註，選填", nullable = true) String note) {

        static EventFields of(CalendarEvent event) {
            return new EventFields(event.title(), event.start(), event.end(), fromCategory(event.category()),
                    event.location().orElse(null), event.note().orElse(null));
        }

        CalendarEvent toDomain() {
            return new CalendarEvent(title, start, end, toCategory(category),
                    Optional.ofNullable(location), Optional.ofNullable(note));
        }
    }

    // 線路上的字串 → domain。沒帶就是 domain 的預設；帶了但不認得是呼叫端送錯（400），不偷偷改成預設
    private static Category toCategory(String text) {
        if (text == null) {
            return Category.DEFAULT;
        }
        return switch (text) {
            case "WORK" -> Category.WORK;
            case "PERSONAL" -> Category.PERSONAL;
            case "HEALTH" -> Category.HEALTH;
            case "SOCIAL" -> Category.SOCIAL;
            default -> throw new IllegalArgumentException("不認得的分類：" + text + "（只接受 WORK、PERSONAL、HEALTH、SOCIAL）");
        };
    }

    // domain → 線路上的字串。沒有 default：Category 多了新值，這裡會編譯失敗，逼你決定它在 API 上叫什麼
    private static String fromCategory(Category category) {
        return switch (category) {
            case WORK -> "WORK";
            case PERSONAL -> "PERSONAL";
            case HEALTH -> "HEALTH";
            case SOCIAL -> "SOCIAL";
        };
    }

    public record AddEventsRequest(@Schema(description = "要存的行程，通常是助理提議、使用者確認過的（可能改過）") List<EventFields> events) {
    }

    public record SavedEvent(
            @Schema(description = "行程的 ID") String id,
            String title,
            LocalDateTime start,
            LocalDateTime end,
            @Schema(allowableValues = {"WORK", "PERSONAL", "HEALTH", "SOCIAL"}) String category,
            @Schema(nullable = true) String location,
            @Schema(nullable = true) String note) {

        static SavedEvent of(ScheduledEvent scheduled) {
            CalendarEvent event = scheduled.event();
            return new SavedEvent(scheduled.id().toString(), event.title(), event.start(), event.end(),
                    fromCategory(event.category()), event.location().orElse(null), event.note().orElse(null));
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
