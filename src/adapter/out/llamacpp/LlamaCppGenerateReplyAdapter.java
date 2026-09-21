package adapter.out.llamacpp;

import application.domain.model.Conversation;
import application.domain.model.Reply;
import application.port.out.GenerateReplyPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * 前面三塊零件（Json、ChatRequest、ChatResponse）全是 package-private，
 * 在這裡被組合起來，然後從外面完全看不到。
 */
public final class LlamaCppGenerateReplyAdapter implements GenerateReplyPort {

    // 路徑是這個 Adapter 的知識，不是呼叫端的 —— 建構子只收 base URI
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    // 連不上要快速失敗：server 沒開的話，等五秒就夠判斷了
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // 但產生回覆可以很慢：Qwen3 的 thinking 會先燒掉幾百個看不見的 token，
    // 給兩分鐘是為了不讓正常的慢被誤判成故障
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final URI endpoint;
    private final Duration requestTimeout;
    // HttpClient 是執行緒安全的，而且內含連線池，所以當欄位重用而不是每次 new
    private final HttpClient httpClient;

    public LlamaCppGenerateReplyAdapter(URI baseUri) {
        this(baseUri, DEFAULT_REQUEST_TIMEOUT);
    }

    public LlamaCppGenerateReplyAdapter(URI baseUri, Duration requestTimeout) {
        Objects.requireNonNull(baseUri, "baseUri 不可為 null");
        this.endpoint = baseUri.resolve(CHAT_COMPLETIONS_PATH);
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout 不可為 null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public Reply generateReply(Optional<String> instruction, List<Conversation.Message> messages) {
        String requestBody = ChatRequest.body(instruction, messages);
        HttpResponse<String> response = send(requestBody);

        if (response.statusCode() / 100 != 2) {
            // llama.cpp 出錯時會在 body 裡放 error 物件，那段訊息比狀態碼有用得多，
            // 所以兩個都帶上。body 可能很長（404 會回整頁 HTML），只取前面一段
            throw new IllegalStateException(
                    "llama.cpp 回應 HTTP " + response.statusCode() + "：" + preview(response.body()));
        }

        return new Reply(ChatResponse.text(response.body()));
    }

    private HttpResponse<String> send(String requestBody) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                // charset 明寫出來：雖然 JSON 本來就規定是 UTF-8，
                // 但寫了才不用賭對方的預設值跟我們一樣
                .header("Content-Type", "application/json; charset=utf-8")
                // 送出時一定要指定 UTF-8。不指定就會用平台預設編碼，
                // 在 Windows 上是 cp950，中文送出去會變成伺服器眼中的亂碼
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            // 讀回來同樣明確指定 UTF-8，理由跟送出時一樣
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // port 契約要求失敗時丟非受檢例外，所以受檢的 IOException 在這裡就包掉。
            // 保留 cause，不然「連線被拒」會變成一句看不出原因的訊息
            throw new IllegalStateException("呼叫 llama.cpp 失敗：" + endpoint, e);
        } catch (InterruptedException e) {
            // send() 被中斷時會吃掉執行緒的中斷旗標，必須自己補回去，
            // 否則上層（例如執行緒池）永遠不知道有人要求停止
            Thread.currentThread().interrupt();
            throw new IllegalStateException("呼叫 llama.cpp 時被中斷：" + endpoint, e);
        }
    }

    private static String preview(String body) {
        if (body == null) {
            return "(沒有內容)";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(已截斷)";
    }
}
