package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddedEvents;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventChangedException;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.in.IdempotencyKeyReusedException;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.UpdateEventUseCase;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一句話變成行程草稿不在這裡：那是 AI 助理的工作（CalendarAssistantController），使用者確認後才送到 POST /events。
 *
 * 誰的行事曆由登入者決定，網址、body 都不收擁有者參數（見 SignedIn）。
 *
 * POST /events 收選填的 Idempotency-Key header，名字照 IETF 草案 draft-ietf-httpapi-idempotency-key-header：
 * 帶了的話，同一個鍵再送一次不會多存，回的是第一次存的那幾筆，回應多一個 Idempotent-Replayed: true。
 * 選填：前後端不一定同時部署，舊版前端不會帶；k6 這類腳本也不必為了壓測自己產生鍵。
 */
@RestController
@RequestMapping("/api/calendar")
@Tag(name = "calendar", description = "行事曆：存下確認過的行程、按期間讀出來畫月曆、拿掉行程")
public final class CalendarController {

    private static final Logger log = LoggerFactory.getLogger(CalendarController.class);

    private final AddEventsUseCase addEvents;
    private final ListEventsUseCase listEvents;
    private final UpdateEventUseCase updateEvent;
    private final RemoveEventUseCase removeEvent;

    public CalendarController(AddEventsUseCase addEvents, ListEventsUseCase listEvents, UpdateEventUseCase updateEvent,
                              RemoveEventUseCase removeEvent) {
        this.addEvents = Objects.requireNonNull(addEvents, "addEvents 不可為 null");
        this.listEvents = Objects.requireNonNull(listEvents, "listEvents 不可為 null");
        this.updateEvent = Objects.requireNonNull(updateEvent, "updateEvent 不可為 null");
        this.removeEvent = Objects.requireNonNull(removeEvent, "removeEvent 不可為 null");
    }

    @PostMapping("/events")
    // 寫成 ResponseEntity：重送時要多帶 header
    @Operation(summary = "把確認過的行程存進行事曆",
            description = "通常是 AI 助理提議、使用者確認過的行程（可能改過），或手動表單填的。全部存或全部不存。")
    @ApiResponse(responseCode = "201", description = "存好了，每筆都有了 id")
    @ApiResponse(responseCode = "400", description = "某筆行程不合規（標題空白、缺時間、結束不晚於開始、分類不認得）或 Idempotency-Key 格式不對，一筆都沒存",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "422", description = "這個 Idempotency-Key 之前存的是另一批不同的行程，這次一筆都沒存；內容改過就換一個新的鍵",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事，一筆都沒存；稍後重試（帶同一個 Idempotency-Key 重試不會多存）",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public ResponseEntity<SavedEvents> add(
            Principal signedIn,
            @Parameter(description = "這一次新增的名字（1～100 個英數或 . _ : -，例如 UUID）。重送時帶同一個就不會多存，"
                    + "回的是第一次存的那幾筆，並帶 Idempotent-Replayed: true。不帶就每次都存")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody AddEventsRequest request) {
        if (request.events() == null) {
            throw new IllegalArgumentException("events 不可為 null");
        }
        // 每一筆都得先成為 CalendarEvent：使用者在確認卡片上改壞的（結束早於開始、清空標題）在這裡就丟 IllegalArgumentException → 400
        List<CalendarEvent> events = request.events().stream().map(CalendarController::toDomain).toList();
        // 是不是真的採納由 use case 核對
        List<ProposalId> adopting = request.events().stream()
                .map(EventFields::proposalId).filter(Objects::nonNull).map(ProposalId::of).toList();
        OwnerId owner = SignedIn.owner(signedIn);
        if (idempotencyKey == null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(SavedEvents.of(addEvents.addEvents(owner, events, adopting)));
        }
        AddedEvents added = addEvents.addEvents(owner, new IdempotencyKey(idempotencyKey), events, adopting);
        // 重送也回 201、內容跟第一次一樣：IETF 草案和 Stripe 都這樣做，呼叫端不必為了重送多寫一條路
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replayed", String.valueOf(added.replayed()))
                .body(SavedEvents.of(added.events()));
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
            Principal signedIn,
            @Parameter(description = "期間開始（含）", example = "2026-09-28")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "期間結束（不含）", example = "2026-11-09")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return SavedEvents.of(listEvents.listEvents(SignedIn.owner(signedIn), from, to));
    }

    @PutMapping("/events/{id}")
    @Operation(summary = "把一個行程改成另一個樣子",
            description = "expected 是改之前看到的樣子（GET 拿到的那筆原樣帶回來），event 是改好的樣子。"
                    + "資料庫裡那筆跟 expected 不一樣，就是在你看到之後被改過了，這次不改、回 409。")
    @ApiResponse(responseCode = "200", description = "改好了，回改好的那筆")
    @ApiResponse(responseCode = "400", description = "id 不是 UUID，或某個欄位不合規（標題空白、結束不晚於開始、分類不認得），沒有改",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "404", description = "你沒有這個行程（可能已經被刪掉了）；別人的行程也是 404",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "409", description = "這筆在你看到之後被改過了，沒有改；重新讀一次再決定要不要改",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事，沒有改；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public SavedEvent update(Principal signedIn, @Parameter(description = "行程的 ID") @PathVariable String id,
                             @RequestBody UpdateEventRequest request) {
        // 格式錯的、規則不合的在這裡就是 400，不會碰到資料庫
        EventId eventId = EventId.of(id);
        CalendarEvent expected = toDomain(request.expected());
        CalendarEvent replacement = toDomain(request.event());
        return SavedEvent.of(updateEvent.updateEvent(SignedIn.owner(signedIn), eventId, expected, replacement));
    }

    @DeleteMapping("/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "從行事曆拿掉一個行程")
    @ApiResponse(responseCode = "204", description = "刪掉了")
    @ApiResponse(responseCode = "400", description = "id 不是合法的 UUID",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "404", description = "你沒有這個行程（可能已經被刪掉了）；重新整理畫面。別人的行程也是 404，不透露它存不存在",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public void remove(Principal signedIn, @Parameter(description = "行程的 ID") @PathVariable String id) {
        // 收 String 自己轉，不讓 Spring 直接轉成 UUID：格式錯時的錯誤訊息才是 EventId 的那句，跟其他 400 一致
        removeEvent.removeEvent(SignedIn.owner(signedIn), EventId.of(id));
    }

    // events 陣列可能夾著 null（[{...}, null]），修改的 expected、event 也可能沒帶；不先擋的話 NullPointerException 會變成 500
    private static CalendarEvent toDomain(EventFields fields) {
        if (fields == null) {
            throw new IllegalArgumentException("行程不可為 null");
        }
        return fields.toDomain();
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<CalendarFailure> onEventNotFound(EventNotFoundException e) {
        log.warn("行程不存在，回 404：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new CalendarFailure(e.getMessage()));
    }

    @ExceptionHandler(EventChangedException.class)
    public ResponseEntity<CalendarFailure> onEventChanged(EventChangedException e) {
        log.warn("行程在呼叫端看到之後被改過了，回 409：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new CalendarFailure(e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<CalendarFailure> onIdempotencyKeyReused(IdempotencyKeyReusedException e) {
        log.warn("同一個 Idempotency-Key 帶了不同的行程，回 422：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new CalendarFailure(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CalendarFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("行事曆請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new CalendarFailure(e.getMessage()));
    }

    // 不攔整個 RuntimeException：攔太寬會把 Spring 自己的 4xx（JSON 格式不對、參數型別不對）誤標成 502
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<CalendarFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("資料庫失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new CalendarFailure(e.getMessage()));
    }

    // ── HTTP/JSON 的格式，不傳進 core ──────────────────────────
    // 名字都帶著業務的字（CalendarFailure，不叫 Failure）：springdoc 用類別的簡單名稱當 schema 名稱，
    // 跟別的 controller 撞名的話，文件裡會互相覆蓋

    /**
     * 助理的提議（proposals）和確認的請求共用這個形狀：前端把卡片原樣（或改過）送回 /events，不必轉換。
     *
     * 分類在 API 上是字串，不直接用 domain 的 Category：enum 改名不該連帶改掉 API 契約，對照寫在 toCategory()、fromCategory()。
     */
    public record EventFields(
            @Schema(description = "要做的事", example = "跟小明吃飯") String title,
            @Schema(description = "開始時間，台北的牆上時間", example = "2026-10-06T15:00:00") LocalDateTime start,
            @Schema(description = "結束時間，必須晚於開始", example = "2026-10-06T16:00:00") LocalDateTime end,
            @Schema(description = "分類；不帶就是 PERSONAL", allowableValues = {"WORK", "PERSONAL", "HEALTH", "SOCIAL"},
                    example = "SOCIAL", nullable = true) String category,
            @Schema(description = "地點，選填", example = "公司附近的拉麵店", nullable = true) String location,
            @Schema(description = "備註，選填", nullable = true) String note,
            @Schema(description = "從哪張助理提議卡片來的（#223）：助理的回覆裡每張卡片都有；確認時原樣帶回來才算採納。手動填的不帶",
                    nullable = true) String proposalId) {

        static EventFields of(CalendarEvent event, ProposalId proposal) {
            return of(event, proposal.toString());
        }

    // 修改卡片只是行程內容，不是要新增的提議，不帶卡片 id
        static EventFields of(CalendarEvent event) {
            return of(event, (String) null);
        }

        private static EventFields of(CalendarEvent event, String proposal) {
            return new EventFields(event.title(), event.start(), event.end(), fromCategory(event.category()),
                    event.location().orElse(null), event.note().orElse(null), proposal);
        }

        CalendarEvent toDomain() {
            return new CalendarEvent(title, start, end, toCategory(category),
                    Optional.ofNullable(location), Optional.ofNullable(note));
        }
    }

    // 沒帶就是 domain 的預設；帶了但不認得是呼叫端送錯（400），不改成預設
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

    // 沒有 default：Category 多了新值，這裡會編譯失敗，強制決定新值在 API 上叫什麼
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

    public record UpdateEventRequest(
            @Schema(description = "改之前看到的樣子（GET 拿到的那筆原樣帶回來，不含 id）", requiredMode = Schema.RequiredMode.REQUIRED) EventFields expected,
            @Schema(description = "改好的樣子", requiredMode = Schema.RequiredMode.REQUIRED) EventFields event) {
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
