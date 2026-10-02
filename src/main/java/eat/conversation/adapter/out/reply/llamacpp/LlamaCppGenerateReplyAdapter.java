package eat.conversation.adapter.out.reply.llamacpp;

import eat.conversation.application.domain.model.Conversation;
import eat.conversation.application.domain.model.Reply;
import eat.conversation.application.port.out.GenerateReplyPort;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用 llama.cpp 的 /v1/chat/completions 產生回覆。
 *
 * 這是這個 package 裡唯一 public 的 class —— 也就是外界唯一看得見的入口。
 * 看得見它的不是 core（core 只認得 GenerateReplyPort），而是組裝根：
 * 整個專案只有那一個地方會 new 它，其餘所有程式都只透過 port 使用它。
 *
 * 兩塊翻譯零件（ChatRequest、ChatResponse）都是 package-private，
 * 在這裡被組合起來，然後從外面完全看不到。
 *
 * HTTP 這一段分兩半，各有各的主人：
 * - 「連到哪、等多久、要不要被觀測」—— 部署決定，由組裝根建好 RestClient 交進來
 *   （從 Spring Boot 的 Builder 建的，所以每次呼叫自動有 HTTP span、指標、traceparent 標頭）
 * - 「打哪個路徑、送什麼、錯誤怎麼翻」—— 協定知識，留在這裡
 */
public final class LlamaCppGenerateReplyAdapter implements GenerateReplyPort {

    // 路徑是這個 Adapter 的知識，不是呼叫端的 —— 組裝根只給 base URL
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    // charset 明寫出來：雖然 JSON 本來就規定是 UTF-8，
    // 但寫了才不用賭對方（和 Spring 的字串轉換器）的預設值跟我們一樣
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    private final RestClient llamaCpp;

    /**
     * llamaCpp：已經指到 llama-server（base URL）、設好逾時的 client。
     * 生成可以很慢（Qwen3 的 thinking 會先燒掉幾百個看不見的 token），讀取逾時要給得寬，
     * 數字在 application.properties 的 llamacpp.readTimeout。
     *
     * 建構子不連線：server 沒開時應用照樣啟動，等有人提問才失敗、回 502。
     */
    public LlamaCppGenerateReplyAdapter(RestClient llamaCpp) {
        this.llamaCpp = Objects.requireNonNull(llamaCpp, "llamaCpp 不可為 null");
    }

    @Override
    public Reply generateReply(Optional<String> instruction, List<Conversation.Message> messages) {
        String requestBody = ChatRequest.body(instruction, messages);
        return new Reply(ChatResponse.text(send(requestBody)));
    }

    private String send(String requestBody) {
        try {
            byte[] responseBody = llamaCpp.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .contentType(JSON_UTF8)
                    // 送 byte 而不是字串：編碼由我們決定（UTF-8），不交給轉換器猜。
                    // 不指定的話，在 Windows 上可能用 cp950 編碼，中文到了伺服器那頭就是亂碼
                    .body(requestBody.getBytes(StandardCharsets.UTF_8))
                    .retrieve()
                    // llama.cpp 出錯時會在 body 裡放 error 物件，那段訊息比狀態碼有用得多，所以兩個都帶上。
                    // 不攔的話 RestClient 會丟它自己的 HttpServerErrorException，port 的契約卻是 IllegalStateException
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new IllegalStateException("llama.cpp 回應 HTTP " + response.getStatusCode().value()
                                + "：" + preview(readUtf8(response.getBody().readAllBytes())));
                    })
                    .body(byte[].class);
            // 讀回來同樣明確用 UTF-8 解，理由跟送出時一樣
            return readUtf8(responseBody);
        } catch (RestClientException e) {
            // 連不上、逾時（RestClient 包成 ResourceAccessException）都在這裡。
            // port 契約要求失敗時丟 IllegalStateException；保留 cause，
            // 它的訊息裡有完整網址和原因（例如 Connection refused），不然會變成一句看不出原因的話
            throw new IllegalStateException("呼叫 llama.cpp 失敗（" + CHAT_COMPLETIONS_PATH + "）：" + e.getMessage(), e);
        }
    }

    // 沒有 body 時 RestClient 給 null，當成空字串：後面的剖析會把它當成壞掉的回應處理
    private static String readUtf8(byte[] body) {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    private static String preview(String body) {
        if (body.isEmpty()) {
            return "(沒有內容)";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(已截斷)";
    }
}
