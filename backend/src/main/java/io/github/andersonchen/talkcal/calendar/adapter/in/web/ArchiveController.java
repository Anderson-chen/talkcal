package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.RestoreEventsUseCase;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.YearMonth;
import java.util.Objects;

/**
 * 從歸檔找回舊資料的 HTTP 入口（#54）：把自己某幾個月的行程和 AI 助理對話放回線上。
 *
 * 只還原登入者自己的（#104）：跟 CalendarController 一樣不收任何「擁有者」參數，別人的歸檔從這裡碰不到。
 * POST 而不是 GET：會寫資料庫。按很多次結果都一樣（已經在線上的不會被蓋掉），所以重試是安全的。
 *
 * 對話的還原不在 core 裡（core 不知道有「對話」這回事），由組裝根接上一個 RestoreConversations 進來 ——
 * 跟 CalendarAssistant 拿 assistantMemory::of 是同一個做法，這個 controller 不認識 adapter.out。
 */
@RestController
@RequestMapping("/api/calendar/archive")
@Tag(name = "archive", description = "歸檔：舊的行程和 AI 助理對話會被搬到物件儲存，需要時從這裡找回來")
public final class ArchiveController {

    private static final Logger log = LoggerFactory.getLogger(ArchiveController.class);

    private final RestoreEventsUseCase restoreEvents;
    private final RestoreConversations restoreConversations;

    public ArchiveController(RestoreEventsUseCase restoreEvents, RestoreConversations restoreConversations) {
        this.restoreEvents = Objects.requireNonNull(restoreEvents, "restoreEvents 不可為 null");
        this.restoreConversations = Objects.requireNonNull(restoreConversations, "restoreConversations 不可為 null");
    }

    @PostMapping("/restore")
    @Operation(summary = "把自己某幾個月的舊資料從歸檔放回來",
            description = "行程依「結束」的月份、對話依「最後說話」的月份；from、to 都含。已經在線上的不會被蓋掉，按幾次結果都一樣。"
                    + "放回來的資料還是舊的，之後的每日歸檔會再把它搬走。")
    @ApiResponse(responseCode = "200", description = "放回來了幾筆行程、幾段對話（0 就是那幾個月沒有歸檔）")
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
        // 行程先：範圍的檢查（from 不晚於 to、最多幾個月）在 RestoreEventsService，不合規在這裡就 400，對話不會先被放回去
        int events = restoreEvents.restore(owner, from, to);
        int conversations = restoreConversations.restore(owner, from, to);
        log.info("從歸檔還原 {} ~ {}：行程 {} 筆、對話 {} 段", from, to, events, conversations);
        return new Restored(events, conversations);
    }

    /** 把 owner 某幾個月（含兩端）的 AI 助理對話放回來，回傳放回幾段。組裝根接上實作。 */
    @FunctionalInterface
    public interface RestoreConversations {

        int restore(OwnerId owner, YearMonth from, YearMonth to);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ArchiveFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("還原請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new ArchiveFailure(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ArchiveFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("還原失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ArchiveFailure(e.getMessage()));
    }

    public record Restored(@Schema(description = "放回行事曆的行程筆數") int events,
                           @Schema(description = "放回去的 AI 助理對話段數") int conversations) {
    }

    public record ArchiveFailure(@Schema(description = "給人看的錯誤原因") String error) {
    }
}
