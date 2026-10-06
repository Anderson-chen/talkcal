package eat.calendar.adapter.in.web;

import eat.calendar.adapter.in.assistant.CalendarAssistant;
import eat.calendar.adapter.in.web.CalendarController.CalendarFailure;
import eat.calendar.adapter.in.web.CalendarController.EventFields;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 行事曆 AI 助理的 HTTP 入口：一句話進來，助理的回覆和它提議的行程出去。
 *
 * 跟 POST /parse 的差別：/parse 是固定流程（一句話一定被當成要新增的行程），
 * 這裡是一段對話 —— 助理可能先反問、可能去查行程或找空檔，也可能提議新增。
 * 提議的行程跟 /parse 回的是同一個形狀（EventFields），畫面照樣顯示卡片、使用者確認後送 POST /events 才存。
 *
 * 多輪對話靠 conversationId（UUID）：第一句不帶，回應裡會拿到一個；之後每句帶著它。歷史存在伺服器。
 * 重新整理頁面後用 GET 讀回那段對話給人看的部分；「清空」用 DELETE。
 */
@RestController
@RequestMapping("/api/calendar/assistant")
@Tag(name = "calendar", description = "行事曆：用一句話新增行程（先預覽、再確認），再按期間讀出來畫月曆")
public final class CalendarAssistantController {

    private static final Logger log = LoggerFactory.getLogger(CalendarAssistantController.class);

    private final CalendarAssistant assistant;

    public CalendarAssistantController(CalendarAssistant assistant) {
        this.assistant = Objects.requireNonNull(assistant, "assistant 不可為 null");
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
    public AssistantResponse talk(@RequestBody AssistantRequest request) {
        String conversationId = conversationId(request.conversationId());
        CalendarAssistant.Reply reply = assistant.reply(conversationId, request.message());
        return new AssistantResponse(conversationId, reply.text(), reply.proposals().stream().map(EventFields::of).toList());
    }

    @GetMapping("/{conversationId}")
    @Operation(summary = "讀回一段對話給人看的部分",
            description = "使用者的話和助理的回覆，照順序。工具呼叫、提議過的卡片不回。沒有這段對話就是空陣列。")
    @ApiResponse(responseCode = "200", description = "這段對話的訊息，可能是空的")
    @ApiResponse(responseCode = "400", description = "conversationId 不是 UUID",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public AssistantHistory history(@PathVariable String conversationId) {
        String id = existingConversationId(conversationId);
        return new AssistantHistory(id, assistant.history(id).stream()
                .map(line -> new AssistantLine(line.fromUser() ? "user" : "assistant", line.text()))
                .toList());
    }

    @DeleteMapping("/{conversationId}")
    // 204，而且本來就沒有也是 204：「清空」要的結果就是「它不在」，按兩次不該報錯
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "清空一段對話")
    @ApiResponse(responseCode = "204", description = "清掉了（本來就沒有也一樣）")
    @ApiResponse(responseCode = "400", description = "conversationId 不是 UUID",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事；稍後重試",
            content = @Content(schema = @Schema(implementation = CalendarFailure.class)))
    public void forget(@PathVariable String conversationId) {
        assistant.forget(existingConversationId(conversationId));
    }

    private static String existingConversationId(String requested) {
        try {
            return UUID.fromString(requested).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("conversationId 不是 UUID：" + requested, e);
        }
    }

    // 沒帶就開新對話；帶了就要是 UUID（spring_ai_chat_memory 的 conversation_id 只放得下 36 個字）
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

    // 名字不跟 ChatController 的 Request／Response 撞：springdoc 用簡單類別名當 schema 名稱
    public record AssistantRequest(
            @Schema(description = "使用者說的話", example = "明天七點和 Amy 見面") String message,
            @Schema(description = "要接續的對話；不帶就開一段新對話", nullable = true) String conversationId) {
    }

    public record AssistantResponse(
            @Schema(description = "這段對話的 ID，下一句帶著它") String conversationId,
            @Schema(description = "助理說的話") String reply,
            @Schema(description = "這一輪提議的行程，使用者確認後送 POST /api/calendar/events 才會存") List<EventFields> proposals) {
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
