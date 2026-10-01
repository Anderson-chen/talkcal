package eat.conversation.adapter.in.web;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.Reply;
import eat.conversation.application.port.in.AskQuestionUseCase;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * 用 HTTP 跟模型對話（inbound adapter）。
 *
 * 這是目前唯一的入口：外面的世界只能透過它使喚核心。
 * 它認得的只有自己那套協定 —— HTTP 與 JSON；業務規則半條都不在這裡。
 *
 * 將來要再加別的入口（另一種協定、排程、訊息佇列），都是各自新增一個 adapter、
 * 一樣只透過 AskQuestionUseCase 這個 inbound port 使喚核心，core 一個字都不用動 ——
 * 這正是六角形架構把入口關在最外圈的用意。
 *
 * 為什麼可以放心讓 Spring 用元件掃描接管它，而不像 llama.cpp adapter 那樣只准組裝根 new？
 * 因為它建構子只認得 AskQuestionUseCase 這個「介面」，不認得任何具體實作。
 * 「用哪個 GenerateReplyPort 實作」的決定權還是牢牢握在組裝根手上；
 * 這裡完全不知道回覆是 llama.cpp 生的還是 OpenAI 生的，換供應商跟它無關。
 *
 * 目前刻意做成「無狀態單輪」：每個請求各自開一段全新的 Conversation、問一題、回一題。
 * 因為 Conversation 是記憶體內的物件、沒有 ID 也沒有持久化，要跨請求記住對話
 * 得先決定歷史放哪（伺服器存 session，還是由前端每次帶完整歷史回來）——
 * 那是獨立的下一步，這步先把「第二個入口能通」這件事單獨做完、單獨驗證。
 */
@RestController
@RequestMapping("/api/chat")
// OpenAPI 的註解只准出現在 adapter 這一圈（ArchitectureTest 守著），它們描述的是 HTTP 長相，不是業務規則
@Tag(name = "chat", description = "跟模型對話：先從知識庫檢索相關片段，再交給模型生成回覆")
public final class ChatController {

    // log 寫在 adapter 這圈：「收到一個 HTTP 請求、回了什麼碼」是入口的事，domain 不必知道有人在看。
    // docker profile 下會印成 ECS JSON，Alloy 收進 Loki 後用 {service="app"} | json 就查得到。
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AskQuestionUseCase askQuestion;

    // 建構子注入：Spring 掃到這個 @RestController，會自動把組裝根宣告的 AskQuestionUseCase 這個
    // @Bean 餵進來。宣告成 final 是刻意的 —— 接好線之後就不該再被換掉。
    public ChatController(AskQuestionUseCase askQuestion) {
        this.askQuestion = Objects.requireNonNull(askQuestion, "askQuestion 不可為 null");
    }

    /**
     * 問一題，拿一題的回覆。
     *
     * 職責刻意很窄：把 JSON 裡的問題挖出來、交給 UseCase、把回覆包成 JSON。
     * 業務規則（文字不可空白、提問與回覆要交替）全在 Conversation 和 AskQuestionService，
     * 這裡半條都不重複寫。
     */
    @PostMapping
    @Operation(summary = "問一題，拿一題的回覆",
            description = "無狀態單輪：每個請求各自開一段新對話，不記得上一題問了什麼。")
    // 三個碼就是這個端點的契約。springdoc 看不到 controller 自己的 @ExceptionHandler，
    // 不寫的話文件只剩 200，看起來像永遠不會失敗；而一旦手寫了任何一個，200 也得自己列。
    // description 是寫給呼叫端的：遇到這個碼該怎麼辦，而不是伺服器內部發生了什麼。
    @ApiResponse(responseCode = "200", description = "拿到模型的回覆")
    @ApiResponse(responseCode = "400", description = "提問是 null 或空白，改好再送",
            content = @Content(schema = @Schema(implementation = Failure.class)))
    @ApiResponse(responseCode = "502", description = "模型或檢索服務沒回應或出錯，不是呼叫端的問題，稍後重試",
            content = @Content(schema = @Schema(implementation = Failure.class)))
    public Response ask(@RequestBody Request request) {
        Conversation conversation = Conversation.start();
        Reply reply = askQuestion.askQuestion(conversation, request.question());
        return new Response(reply.text());
    }

    /**
     * 提問不合規（null 或空白）是「呼叫端送錯東西」，對應 400。
     * 這條規則由 model（Question）以 IllegalArgumentException 擋下，我們只負責翻成 HTTP 的語言。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Failure> onInvalidQuestion(IllegalArgumentException e) {
        // WARN 不是 ERROR：呼叫端送錯是預期中的事，伺服器沒壞
        log.warn("提問不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new Failure(e.getMessage()));
    }

    /**
     * 模型那頭出事（連不上 llama.cpp、回了非 2xx、逾時）時，llama.cpp adapter 會丟
     * IllegalStateException，AskQuestionService 原樣往外拋。那不是呼叫端的錯，是上游服務的問題，
     * 對應 502 Bad Gateway 比籠統的 500 更誠實。這次的 Conversation 本來就是這次請求才建的、
     * 用完即丟，所以不必再收拾狀態。
     *
     * 這裡刻意只攔 IllegalStateException，不攔整個 RuntimeException ——
     * 一開始貪方便攔了 RuntimeException，結果連 Spring 自己「JSON 格式不對」丟的
     * HttpMessageNotReadableException 都被吃進來、誤標成 502（那明明是呼叫端送錯，該 400）。
     * 收窄之後，請求格式的錯就交還給 Spring 用正確的 4xx 回應。
     * （更乾淨的做法是讓 outbound port 丟一個專屬的失敗例外型別，等要收斂錯誤處理時再做。）
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Failure> onModelFailure(IllegalStateException e) {
        // ERROR 並附上例外：上游真的出事了，要看得到 stack trace 才查得到是連不上還是逾時
        log.error("模型或檢索服務失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Failure(e.getMessage()));
    }

    // 這個 adapter 自己的對外資料格式（wire format），只服務 HTTP/JSON，不外流到 core。
    // 用巢狀 record 表明「它們是 ChatController 的請求/回應形狀」，而不是通用領域型別。
    // @Schema 寫在這裡而不是 Question / Reply 上：文件描述的是線路格式，domain 不該替 HTTP 打扮。
    public record Request(
            @Schema(description = "要問模型的問題，不可空白", example = "雞胸肉一百克有多少蛋白質？")
            String question) {
    }

    public record Response(
            @Schema(description = "模型的回覆") String reply) {
    }

    public record Failure(
            @Schema(description = "給人看的錯誤原因") String error) {
    }
}
