package eat.conversation.adapter.in.web;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.Reply;
import eat.conversation.application.port.in.AskQuestionUseCase;

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
public final class ChatController {

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
    public Response ask(@RequestBody Request request) {
        Conversation conversation = Conversation.start();
        Reply reply = askQuestion.askQuestion(conversation, request.question());
        return new Response(reply.text());
    }

    /**
     * 提問不合規（null 或空白）是「呼叫端送錯東西」，對應 400。
     * 這條規則由 Conversation 以 IllegalArgumentException 擋下，我們只負責翻成 HTTP 的語言。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Failure> onInvalidQuestion(IllegalArgumentException e) {
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
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Failure(e.getMessage()));
    }

    // 這個 adapter 自己的對外資料格式（wire format），只服務 HTTP/JSON，不外流到 core。
    // 用巢狀 record 表明「它們是 ChatController 的請求/回應形狀」，而不是通用領域型別。
    public record Request(String question) {
    }

    public record Response(String reply) {
    }

    public record Failure(String error) {
    }
}
