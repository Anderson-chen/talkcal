package eat.conversation.adapter.in.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * 用 HTTP 跟模型對話。
 *
 * conversation 沒有自己的 domain 了，這個 controller 直接使喚 Spring AI 的 ChatClient：
 * 歷史（ChatMemory）、檢索（RAG）都是 ConversationConfiguration 掛在 ChatClient 上的 advisor，
 * 這裡只負責 HTTP 那一層 —— 把問題和 conversationId 挖出來、交給 ChatClient、把回覆包成 JSON。
 *
 * 多輪對話靠 conversationId：第一題不帶，回應裡會拿到一個；之後每題帶著它，就是接續同一段對話。
 * 歷史存在伺服器（PostgreSQL），呼叫端不必每次把整段歷史送回來。
 * 帶了一個沒見過的 ID，就是從那個 ID 開始一段新對話 —— ChatMemory 沒有「對話不存在」這回事。
 */
@RestController
@RequestMapping("/api/chat")
// OpenAPI 的註解只准出現在 adapter 這一圈（ArchitectureTest 守著），它們描述的是 HTTP 長相
@Tag(name = "chat", description = "跟模型對話：先從知識庫檢索相關片段，再交給模型生成回覆")
public final class ChatController {

    // docker profile 下會印成 ECS JSON，Alloy 收進 Loki 後用 {service="app"} | json 就查得到。
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient 不可為 null");
    }

    /**
     * 問一題，拿一題的回覆。
     */
    @PostMapping
    @Operation(summary = "問一題，拿一題的回覆",
            description = "不帶 conversationId 就開一段新對話；帶著上一題回應裡的 conversationId，模型就看得到之前的問答。")
    // 三個碼就是這個端點的契約。springdoc 看不到 controller 自己的 @ExceptionHandler，
    // 不寫的話文件只剩 200，看起來像永遠不會失敗；而一旦手寫了任何一個，200 也得自己列。
    @ApiResponse(responseCode = "200", description = "拿到模型的回覆")
    @ApiResponse(responseCode = "400", description = "提問是 null 或空白，或 conversationId 不是 UUID，改好再送",
            content = @Content(schema = @Schema(implementation = Failure.class)))
    @ApiResponse(responseCode = "502", description = "模型、檢索或資料庫沒回應或出錯，不是呼叫端的問題，稍後重試",
            content = @Content(schema = @Schema(implementation = Failure.class)))
    public Response ask(@RequestBody Request request) {
        // 先檢查完才碰 ChatClient：空白提問送出去，換來的只會是一個跟呼叫端無關的上游錯誤
        // （server 沒開時甚至是 502 而不是 400）
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("提問不可為 null 或空白");
        }
        String conversationId = conversationId(request.conversationId());
        return new Response(conversationId, reply(conversationId, request.question()));
    }

    // 沒帶就開新對話。帶了就必須是 UUID：spring_ai_chat_memory.conversation_id 是 VARCHAR(36)，
    // 不檢查的話太長的 ID 要到存檔時才在資料庫炸成 502，而那明明是呼叫端送錯
    private static String conversationId(String requested) {
        if (requested == null) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(requested).toString();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("conversationId 不是 UUID：" + requested, e);
        }
    }

    private String reply(String conversationId, String question) {
        String reply;
        try {
            reply = chatClient.prompt()
                    .user(question)
                    // 告訴記憶那個 advisor 這一題屬於哪段對話：讀歷史、存這一輪都用它
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
        } catch (RuntimeException e) {
            // ChatClient 背後是模型、embedding、資料庫三台 server，任何一台出事丟出來的例外型別都不一樣
            // （OpenAI SDK 的、Spring JDBC 的、Spring AI 自己的），而且裡面可能混著 IllegalArgumentException。
            // 全部包成同一種，才不會被下面的 400 handler 誤認成「呼叫端送錯」
            throw new UpstreamFailureException(e);
        }
        if (reply == null || reply.isBlank()) {
            throw new UpstreamFailureException(new IllegalStateException("模型沒有產生任何內容"));
        }
        return reply;
    }

    /**
     * 呼叫端送錯東西（提問空白、conversationId 格式不對），對應 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Failure> onInvalidRequest(IllegalArgumentException e) {
        // WARN 不是 ERROR：呼叫端送錯是預期中的事，伺服器沒壞
        log.warn("請求不合規，回 400：{}", e.getMessage());
        return ResponseEntity.badRequest().body(new Failure(e.getMessage()));
    }

    /**
     * 模型、檢索或資料庫那頭出事。不是呼叫端的錯，是上游服務的問題，對應 502 Bad Gateway。
     * 只攔自己包出來的那一種：Spring 自己「JSON 格式不對」丟的例外照樣交給 Spring 回 4xx。
     */
    @ExceptionHandler(UpstreamFailureException.class)
    public ResponseEntity<Failure> onUpstreamFailure(UpstreamFailureException e) {
        // ERROR 並附上例外：上游真的出事了，要看得到 stack trace 才查得到是連不上還是逾時
        log.error("模型、檢索或資料庫失敗，回 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new Failure(e.getMessage()));
    }

    // 只在這個 controller 裡用：把「ChatClient 失敗」跟「呼叫端送錯」分開
    static final class UpstreamFailureException extends RuntimeException {
        UpstreamFailureException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    // 這個 adapter 自己的對外資料格式（wire format），只服務 HTTP/JSON。
    public record Request(
            @Schema(description = "要問模型的問題，不可空白", example = "雞胸肉一百克有多少蛋白質？")
            String question,
            @Schema(description = "要接續的對話；不帶就開一段新對話", example = "3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10",
                    nullable = true)
            String conversationId) {
    }

    public record Response(
            @Schema(description = "這段對話的 ID，下一題帶著它就是接續同一段對話") String conversationId,
            @Schema(description = "模型的回覆") String reply) {
    }

    public record Failure(
            @Schema(description = "給人看的錯誤原因") String error) {
    }
}
