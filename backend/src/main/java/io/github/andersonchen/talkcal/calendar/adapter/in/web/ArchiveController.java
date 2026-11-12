package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.adapter.in.web.CalendarController.SavedEvents;
import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListArchivedEventsUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/**
 * 歸檔的 HTTP 入口（#54）：查看封存的行程（#274），和把自己某幾個月的 AI 助理對話放回線上。
 *
 * 只碰登入者自己的（#104）：跟 CalendarController 一樣不收任何「擁有者」參數，別人的歸檔從這裡碰不到。
 * - 行程：GET，只讀。封存的行程不放回資料庫，放回去隔天的排程又會再封存一次
 * - 對話：POST，會寫資料庫。按很多次結果都一樣（已經在線上的不會被蓋掉），所以重試是安全的
 *
 * 對話的還原不在 core 裡（core 不知道有「對話」這回事），由組裝根接上一個 RestoreConversations 進來 ——
 * 跟 CalendarAssistant 拿 assistantMemory::of 是同一個做法，這個 controller 不認識 adapter.out。
 *
 * 回傳的行程跟月曆同一個形狀（CalendarController.SavedEvents），前端用同一個型別畫。
 */
@RestController
@RequestMapping("/api/calendar/archive")
@Tag(name = "archive", description = "歸檔：舊的行程和 AI 助理對話會被搬到物件儲存；行程照日期查看，對話需要時放回來")
public final class ArchiveController {

    private static final Logger log = LoggerFactory.getLogger(ArchiveController.class);

    // 502 給呼叫端看的話：例外訊息裡有 bucket、key、擁有者 id，只寫進 log，不回給瀏覽器
    static final String UPSTREAM_FAILURE = "封存的資料暫時讀不到，請稍後再試";

    private final ListArchivedEventsUseCase listArchivedEvents;
    private final RestoreConversations restoreConversations;

    public ArchiveController(ListArchivedEventsUseCase listArchivedEvents, RestoreConversations restoreConversations) {
        this.listArchivedEvents = Objects.requireNonNull(listArchivedEvents, "listArchivedEvents 不可為 null");
        this.restoreConversations = Objects.requireNonNull(restoreConversations, "restoreConversations 不可為 null");
    }

    @GetMapping("/events")
    @Operation(summary = "查看自己已經封存的行程（只讀）",
            description = "結束超過保留期限的行程每天會被搬到歸檔，月曆上就看不到了；這裡照日期查。from、to 都含，看一天就是同一天。"
                    + "跟範圍有重疊的都算（跟月曆一樣），依開始時間排序。只讀，不會放回行事曆。")
    @ApiResponse(responseCode = "200", description = "範圍裡封存的行程，可能是空的")
    @ApiResponse(responseCode = "400", description = "範圍不合規：缺參數、格式不是 yyyy-MM-dd、from 晚於 to、超過 24 個月",
            content = @Content(schema = @Schema(implementation = ArchiveFailure.class)))
    @ApiResponse(responseCode = "502", description = "物件儲存出事；稍後重試。月曆上的行程不受影響",
            content = @Content(schema = @Schema(implementation = ArchiveFailure.class)))
    public SavedEvents events(
            Principal signedIn,
            @Parameter(description = "開始日期（含）", example = "2024-03-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "結束日期（含）", example = "2024-03-05")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return SavedEvents.of(listArchivedEvents.listArchivedEvents(SignedIn.owner(signedIn), from, to));
    }

    @PostMapping("/restore")
    @Operation(summary = "把自己某幾個月的 AI 助理對話從歸檔放回來",
            description = "依「最後說話」的月份；from、to 都含。已經在線上的不會被蓋掉，按幾次結果都一樣。"
                    + "放回來的對話還是舊的，之後的每日歸檔會再把它搬走。行程不放回來，用 GET /events 查看。")
    @ApiResponse(responseCode = "200", description = "放回來了幾段對話（0 就是那幾個月沒有歸檔）")
    @ApiResponse(responseCode = "400", description = "月份不合規：格式不是 yyyy-MM、from 晚於 to、超過 24 個月",
            content = @Content(schema = @Schema(implementation = ArchiveFailure.class)))
    @ApiResponse(responseCode = "502", description = "物件儲存或資料庫出事；稍後重試（重試是安全的）",
            content = @Content(schema = @Schema(implementation = ArchiveFailure.class)))
    public Restored restore(
            Principal signedIn,
            @Parameter(description = "起始月份（含）", example = "2024-01")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth from,
            @Parameter(description = "結束月份（含）", example = "2024-06")
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth to) {
        OwnerId owner = SignedIn.owner(signedIn);
        // 範圍不合規（from 晚於 to、超過 24 個月）在這一行就是 IllegalArgumentException → 400，還沒碰到歸檔
        MonthRange months = new MonthRange(from, to);
        int conversations = restoreConversations.restore(owner, months);
        log.info("從歸檔還原 {} ~ {}：對話 {} 段", from, to, conversations);
        return new Restored(conversations);
    }

    /** 把 owner 某幾個月（兩端都含）的 AI 助理對話放回來，回傳放回幾段。組裝根接上實作。 */
    @FunctionalInterface
    public interface RestoreConversations {

        int restore(OwnerId owner, MonthRange months);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ArchiveFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("歸檔的請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new ArchiveFailure(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ArchiveFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("讀寫歸檔失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ArchiveFailure(UPSTREAM_FAILURE));
    }

    public record Restored(@Schema(description = "放回去的 AI 助理對話段數") int conversations) {
    }

    public record ArchiveFailure(@Schema(description = "給人看的錯誤原因") String error) {
    }
}
