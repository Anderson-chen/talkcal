package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.adapter.in.web.CalendarController.EventFields;
import io.github.andersonchen.talkcal.calendar.application.port.in.GuestDemoLimitException;
import io.github.andersonchen.talkcal.calendar.application.port.in.ShowGuestDemoUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 沒登入也能用（SecurityConfiguration 開放）：訪客點固定問題，回預先寫好的答案，不呼叫模型。
 *
 * 訪客用 request.getRemoteAddr() 區分，不自己讀 X-Forwarded-For。server.forward-headers-strategy=native 時，
 * Tomcat 只在請求來自內部網段（nginx、Caddy）時才照 X-Forwarded-For 改寫 remoteAddr，而且從右邊數回來，
 * 跳過內部網段，取第一個外部的位址。使用者自己在最左邊塞的假位址輪不到，換不到新的次數。
 * 自己讀這個 header 的話，取最左邊就會被偽造，取最右邊在有 Caddy 時又會變成 Caddy 的位址。
 *
 * 失敗一律回 {"error", "code"}，跟 AssistantFailure 同一個格式：
 * <pre>
 * 400  INVALID_REQUEST  不是固定的問題。不算一次
 * 429  GUEST_LIMIT      這個 IP 今天看滿了。Retry-After 是離換日還有幾秒
 * </pre>
 */
@RestController
@RequestMapping("/api/demo/assistant")
@Tag(name = "demo", description = "沒登入的訪客看 AI 助理的示範回答")
public final class GuestDemoController {

    private static final Logger log = LoggerFactory.getLogger(GuestDemoController.class);

    private final ShowGuestDemoUseCase demo;

    public GuestDemoController(ShowGuestDemoUseCase demo) {
        this.demo = Objects.requireNonNull(demo, "demo 不可為 null");
    }

    @PostMapping
    @Operation(summary = "訪客點固定問題，看助理預先寫好的回答",
            description = "不用登入、不呼叫模型。只收對話框的 3 個固定問題，每個 IP 每天 3 次。回答裡的日期照當天算。"
                    + "proposals 只給畫面看，要存進行事曆得先登入。")
    @ApiResponse(responseCode = "200", description = "預先寫好的回答，以及今天還能看幾次")
    @ApiResponse(responseCode = "400", description = "不是固定的問題（code = INVALID_REQUEST），不算一次",
            content = @Content(schema = @Schema(implementation = GuestDemoFailure.class)))
    @ApiResponse(responseCode = "429", description = "這個 IP 今天看滿了（code = GUEST_LIMIT）；Retry-After 是離換日還有幾秒",
            content = @Content(schema = @Schema(implementation = GuestDemoFailure.class)))
    public GuestDemoResponse show(HttpServletRequest request, @RequestBody GuestDemoRequest body) {
        ShowGuestDemoUseCase.Shown shown = demo.show(request.getRemoteAddr(), body.message());
        List<EventFields> cards = shown.answer().proposals().stream().map(EventFields::of).toList();
        return new GuestDemoResponse(shown.answer().reply(), cards, shown.remaining());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<GuestDemoFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("訪客示範的請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new GuestDemoFailure(e.getMessage(), "INVALID_REQUEST"));
    }

    // 看滿是預期中的事，不記 warn
    @ExceptionHandler(GuestDemoLimitException.class)
    public ResponseEntity<GuestDemoFailure> onLimit(GuestDemoLimitException e) {
        long seconds = Math.max(1, e.retryAfter().toSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new GuestDemoFailure(e.getMessage(), "GUEST_LIMIT"));
    }

    public record GuestDemoRequest(
            @Schema(description = "按下的固定問題，跟按鈕上的文字一樣", example = "明天有什麼行程？") String message) {
    }

    public record GuestDemoResponse(
            @Schema(description = "助理預先寫好的回答") String reply,
            @Schema(description = "提議的行程卡片；訪客按確認時前端帶去登入，這裡不存") List<EventFields> proposals,
            @Schema(description = "這個 IP 今天還能看幾次") int remaining) {
    }

    public record GuestDemoFailure(
            @Schema(description = "給人看的錯誤原因") String error,
            @Schema(description = "給程式判斷的錯誤碼", allowableValues = {"INVALID_REQUEST", "GUEST_LIMIT"}) String code) {
    }
}
