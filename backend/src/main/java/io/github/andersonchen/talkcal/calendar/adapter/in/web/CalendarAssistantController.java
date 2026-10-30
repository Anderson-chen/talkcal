package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import io.github.andersonchen.talkcal.calendar.adapter.in.assistant.CalendarAssistant;
import io.github.andersonchen.talkcal.calendar.adapter.in.web.CalendarController.EventFields;
import io.github.andersonchen.talkcal.calendar.adapter.shared.ModelUnavailableException;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
import java.util.stream.IntStream;

/**
 * 行事曆 AI 助理的 HTTP 入口：一句話進來，助理的回覆和它提議的行程出去。
 *
 * 這裡是一段對話 —— 助理可能先反問、可能去查行程或找空檔，也可能提議新增。
 * 提議的行程跟 POST /events 收的是同一個形狀（EventFields），畫面顯示卡片、使用者確認後送 POST /events 才存。
 *
 * 多輪對話靠 conversationId（UUID）：第一句不帶，回應裡會拿到一個；之後每句帶著它。歷史存在伺服器。
 * 重新整理頁面後用 GET 讀回那段對話給人看的部分。
 * 沒有 DELETE：「清空」是前端丟掉 conversationId、下一句開新的一段；舊的那段留給每天的歸檔排程（AssistantMemoryArchive）。
 * 紀錄只由排程按保留期限刪，不讓一個按鈕（或任何拿得到 id 的請求）把它抹掉。
 *
 * 對話屬於登入者（#104）：拿別人的 conversationId 來讀是空的、拿來說話是在自己名下開一段新的，碰不到別人的那段。
 *
 * 卡片的去向記成產品指標（#36）：回覆時每張卡片發一個 proposalId 並記下來（#223）；
 * 按「加入行事曆」走 POST /events，每筆帶著它的 proposalId；按「取消」「移除」只改畫面，所以前端另外送 POST /dismissals 帶那幾張的 id。
 * 伺服器只認自己發過、屬於登入者、還沒表態過的卡片，前端說了不算。
 *
 * 失敗一律回 AssistantFailure：error 給人看，code 給程式判斷（前端依它決定怎麼顯示、要不要讓使用者重試，#43）。
 * <pre>
 * 400  INVALID_REQUEST    訊息空白、conversationId 不是 UUID。改了請求才會好
 * 502  ASSISTANT_FAILED   這一次叫模型（或資料庫）失敗了。可以馬上再試
 * 503  MODEL_UNAVAILABLE  熔斷打開：模型最近一直失敗，這次根本沒叫它。Retry-After 秒後再試
 * 503  MODEL_BUSY         同時在等模型的請求滿了，這次沒叫它。Retry-After 秒後再試
 * </pre>
 * 503 跟 502 分開：502 是「叫了、壞了」，503 是「知道叫了也沒用，先不叫」—— 前端看到 503 就不該讓使用者一直按重試。
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
    @ApiResponse(responseCode = "400", description = "訊息是 null 或空白，或 conversationId 不是 UUID（code = INVALID_REQUEST）",
            content = @Content(schema = @Schema(implementation = AssistantFailure.class)))
    @ApiResponse(responseCode = "502", description = "這一次叫模型或資料庫失敗；可以再試（code = ASSISTANT_FAILED）",
            content = @Content(schema = @Schema(implementation = AssistantFailure.class)))
    @ApiResponse(responseCode = "503", description = "模型暫時不能用，這次沒有叫它（code = MODEL_UNAVAILABLE 或 MODEL_BUSY）；"
            + "Retry-After 標頭和 retryAfterSeconds 是建議多久之後再試。行程 API 不受影響",
            content = @Content(schema = @Schema(implementation = AssistantFailure.class)))
    public AssistantResponse talk(Principal signedIn, @RequestBody AssistantRequest request) {
        String conversationId = conversationId(request.conversationId());
        OwnerId owner = SignedIn.owner(signedIn);
        CalendarAssistant.Reply reply = assistant.reply(owner, conversationId, request.message());
        // 在這裡發 id、不在助理呼叫工具時發：工具解析出來的草稿可能被擋下（沒講早上晚上），也可能這一輪最後失敗（502），
        // 那些使用者都沒看到。回得出去的這一份，才是畫面上真的出現的卡片
        List<CalendarEvent> drafts = reply.proposals();
        List<ProposalId> ids = proposals.proposalsShown(owner, drafts.size());
        List<EventFields> cards = IntStream.range(0, drafts.size())
                .mapToObj(i -> EventFields.of(drafts.get(i), ids.get(i)))
                .toList();
        return new AssistantResponse(conversationId, reply.text(), cards);
    }

    @PostMapping("/dismissals")
    // 204：記下了，沒有東西要回
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "回報使用者拒絕了哪幾張提議卡片",
            description = "按「取消」「移除」只改畫面、不經過後端，前端用這個告訴後端，產品指標才算得出採納率。不影響任何行程。"
                    + "只有自己的、還沒表態過的卡片才算，每張最多一次；別人的、沒發過的、已表態過的都靜靜略過。")
    @ApiResponse(responseCode = "204", description = "收到了（不透露每一張有沒有算）")
    @ApiResponse(responseCode = "400", description = "沒帶 proposalIds，或某個 id 不是 UUID（code = INVALID_REQUEST）",
            content = @Content(schema = @Schema(implementation = AssistantFailure.class)))
    public void dismissed(Principal signedIn, @RequestBody AssistantDismissals request) {
        if (request.proposalIds() == null) {
            throw new IllegalArgumentException("proposalIds 不可為 null");
        }
        proposals.proposalsDismissed(SignedIn.owner(signedIn), request.proposalIds().stream().map(ProposalId::of).toList());
    }

    @GetMapping("/{conversationId}")
    @Operation(summary = "讀回一段對話給人看的部分",
            description = "使用者的話和助理的回覆，照順序。工具呼叫、提議過的卡片不回。沒有這段對話（或不是你的）就是空陣列。")
    @ApiResponse(responseCode = "200", description = "這段對話的訊息，可能是空的")
    @ApiResponse(responseCode = "400", description = "conversationId 不是 UUID（code = INVALID_REQUEST）",
            content = @Content(schema = @Schema(implementation = AssistantFailure.class)))
    @ApiResponse(responseCode = "502", description = "資料庫出事；稍後重試（code = ASSISTANT_FAILED）",
            content = @Content(schema = @Schema(implementation = AssistantFailure.class)))
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
    public ResponseEntity<AssistantFailure> onInvalidInput(IllegalArgumentException e) {
        log.warn("AI 助理請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(AssistantFailure.of(e.getMessage(), "INVALID_REQUEST"));
    }

    // 模型沒被叫就擋下（#44）。比下面的 IllegalStateException 具體，Spring 會先挑這個。
    // 只記一行 warn、不印 stack：熔斷打開時每個請求都會走到這裡，原因已經在「熔斷狀態改變」那行 log 和指標上了
    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<AssistantFailure> onModelUnavailable(ModelUnavailableException e) {
        long seconds = Math.max(1, e.retryAfter().toSeconds());
        String code = switch (e.reason()) {
            case CIRCUIT_OPEN -> "MODEL_UNAVAILABLE";
            case BUSY -> "MODEL_BUSY";
        };
        log.warn("AI 助理沒有叫模型，回 503：code={} retryAfter={}s", code, seconds);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
                .body(new AssistantFailure(e.getMessage(), code, seconds));
    }

    // 只攔 IllegalStateException（CalendarAssistant 把上游的各種例外都包成這個），理由同 CalendarController
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AssistantFailure> onUpstreamFailure(IllegalStateException e) {
        log.error("AI 助理失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(AssistantFailure.of(e.getMessage(), "ASSISTANT_FAILED"));
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

    public record AssistantDismissals(
            @Schema(description = "這次按掉的卡片（回覆裡 proposals 的 proposalId）", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> proposalIds) {
    }

    public record AssistantHistory(
            @Schema(description = "這段對話的 ID") String conversationId,
            @Schema(description = "給人看的訊息，照順序") List<AssistantLine> messages) {
    }

    /**
     * 助理的錯誤回應。error 跟 CalendarController 的 CalendarFailure 同名同義，只看 error 的前端不必改；
     * 多出來的 code、retryAfterSeconds 給要分辨「這次失敗」和「現在不能用」的前端（#43）。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssistantFailure(
            @Schema(description = "給人看的錯誤原因") String error,
            @Schema(description = "給程式判斷的錯誤碼",
                    allowableValues = {"INVALID_REQUEST", "ASSISTANT_FAILED", "MODEL_UNAVAILABLE", "MODEL_BUSY"}) String code,
            @Schema(description = "建議幾秒後再試；只有 503 才有，跟 Retry-After 標頭一樣", nullable = true) Long retryAfterSeconds) {

        static AssistantFailure of(String error, String code) {
            return new AssistantFailure(error, code, null);
        }
    }

    public record AssistantLine(
            @Schema(description = "誰說的", allowableValues = {"user", "assistant"}) String role,
            @Schema(description = "說了什麼") String text) {
    }
}
