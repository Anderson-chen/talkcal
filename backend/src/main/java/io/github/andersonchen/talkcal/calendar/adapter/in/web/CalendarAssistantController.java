package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.adapter.in.assistant.CalendarAssistant;
import io.github.andersonchen.talkcal.calendar.adapter.in.web.CalendarController.CalendarFailure;
import io.github.andersonchen.talkcal.calendar.adapter.in.web.CalendarController.EventFields;
import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 行事曆 AI 助理的 HTTP 入口：一句話進來，助理的回覆和它提議的行程出去。
 *
 * 這裡是一段對話 —— 助理可能先反問、可能去查行程或找空檔，也可能提議新增。
 * 提議的行程跟 POST /events 收的是同一個形狀（EventFields），畫面顯示卡片、使用者確認後送 POST /events 才存。
 *
 * 多輪對話靠 conversationId（UUID）：第一句不帶，回應裡會拿到一個；之後每句帶著它。歷史存在伺服器。
 * 重新整理頁面後用 GET 讀回那段對話給人看的部分。
 * 沒有 DELETE：「清空」是前端丟掉 conversationId、下一句開新的一段；舊的那段留給每天的清理排程（AssistantMemoryCleanup）。
 * 紀錄只由排程按保留期限刪，不讓一個按鈕（或任何拿得到 id 的請求）把它抹掉。
 *
 * 對話屬於登入者（#104）：拿別人的 conversationId 來讀是空的、拿來說話是在自己名下開一段新的，碰不到別人的那段。
 *
 * 卡片的去向記成產品指標（#36）：回覆時記給人看了幾張；按「加入行事曆」走 POST /events（帶 origin=ASSISTANT）；
 * 按「取消」「移除」只改畫面，所以前端另外送 POST /dismissals 告訴後端被拒絕了幾張。
 */
@RestController
@RequestMapping("/api/calendar/assistant")
@Tag(name = "calendar", description = "行事曆：用一句話新增行程（先預覽、再確認），再按期間讀出來畫月曆")
public final class CalendarAssistantController {

    private static final Logger log = LoggerFactory.getLogger(CalendarAssistantController.class);

    private final CalendarAssistant assistant;
    private final TrackProposalsUseCase proposals;

    public CalendarAssistantController(CalendarAssistant assistant, TrackProposalsUseCase proposals) {
        this.assistant = Objects.requireNonNull(assistant, "assistant 不可為 null");
        this.proposals = Objects.requireNonNull(proposals, "proposals 不可為 null");
    }

    @PostMapping
    @Operation(summary = "跟行事曆 AI 助理說一句話",
            description = "助理可能直接回答（查行程、找空檔）、反問（時間不確定時），或提議行程（proposals，使用者確認後送 POST /api/calendar/events 才存）。"
                    + "不帶 conversationId 就開一段新對話。")
    @ApiResponse(responseCode = "200", description = "助理的回覆，以及這一輪提議的行程（可能是空的）")
    @ApiResponse(responseCode = "400", description = "訊息是 null 或空白，或 conversationId 不是 UUID",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "模型或資料庫出事；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public AssistantResponse talk(Principal signedIn, @RequestBody AssistantRequest request) {
        String conversationId = conversationId(request.conversationId());
        CalendarAssistant.Reply reply = assistant.reply(SignedIn.owner(signedIn), conversationId, request.message());
        // 在這裡數、不在助理呼叫工具時數：工具解析出來的草稿可能被擋下（沒講早上晚上），也可能這一輪最後失敗（502），
        // 那些使用者都沒看到。回得出去的這一份，才是畫面上真的出現的卡片
        proposals.proposalsShown(reply.proposals().size());
        return new AssistantResponse(conversationId, reply.text(), reply.proposals().stream().map(EventFields::of).toList());
    }

    @PostMapping("/dismissals")
    // 204：記下了，沒有東西要回
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "回報使用者拒絕了幾張提議卡片",
            description = "按「取消」「移除」只改畫面、不經過後端，前端用這個告訴後端，產品指標才算得出採納率。不影響任何資料。")
    @ApiResponse(responseCode = "204", description = "記下了")
    @ApiResponse(responseCode = "400", description = "count 小於 0 或超過 " + TrackProposalsUseCase.MAX_DISMISSED_PER_CALL,
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public void dismissed(@RequestBody AssistantDismissals request) {
        proposals.proposalsDismissed(request.count());
    }

    @GetMapping("/{conversationId}")
    @Operation(summary = "讀回一段對話給人看的部分",
            description = "使用者的話和助理的回覆，照順序。工具呼叫、提議過的卡片不回。沒有這段對話（或不是你的）就是空陣列。")
    @ApiResponse(responseCode = "200", description = "這段對話的訊息，可能是空的")
    @ApiResponse(responseCode = "400", description = "conversationId 不是 UUID",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public AssistantHistory history(Principal signedIn, @PathVariable String conversationId) {
        String id = existingConversationId(conversationId);
        return new AssistantHistory(id, assistant.history(SignedIn.owner(signedIn), id).stream()
                .map(line -> new AssistantLine(line.fromUser() ? "user" : "assistant", line.text()))
                .toList());
    }

    private static String existingConversationId(String requested) {
        try {
            return UUID.fromString(requested).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("conversationId 不是 UUID：" + requested, e);
        }
    }

    // 沒帶就開新對話；帶了就要是 UUID（calendar_assistant_message 的 conversation_id 是 UUID 欄位，見 V7）
    private static String conversationId(String requested) {
        if (requested == null || requested.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(requested).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("conversationId 不是 UUID：" + requested, e);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CalendarFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("AI 助理請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new CalendarFailure(e.getMessage()));
    }

    // 只攔 IllegalStateException（CalendarAssistant 把上游的各種例外都包成這個），理由同 CalendarController
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<CalendarFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("AI 助理失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new CalendarFailure(e.getMessage()));
    }

    // 名字帶著 Assistant，不叫 Request／Response：springdoc 用簡單類別名當 schema 名稱，太通用的名字遲早撞名
    public record AssistantRequest(
            @Schema(description = "使用者說的話", example = "明天七點和 Amy 見面") String message,
            @Schema(description = "要接續的對話；不帶就開一段新對話", nullable = true) String conversationId) {
    }

    public record AssistantResponse(
            @Schema(description = "這段對話的 ID，下一句帶著它") String conversationId,
            @Schema(description = "助理說的話") String reply,
            @Schema(description = "這一輪提議的行程，使用者確認後送 POST /api/calendar/events 才會存") List<EventFields> proposals) {
    }

    public record AssistantDismissals(@Schema(description = "這次按掉了幾張卡片", example = "1") int count) {
    }

    public record AssistantHistory(
            @Schema(description = "這段對話的 ID") String conversationId,
            @Schema(description = "給人看的訊息，照順序") List<AssistantLine> messages) {
    }

    public record AssistantLine(
            @Schema(description = "誰說的", allowableValues = {"user", "assistant"}) String role,
            @Schema(description = "說了什麼") String text) {
    }
}
