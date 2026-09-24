package eat.conversation.adapter.out.knowledge.llamacpp;

import eat.conversation.adapter.out.knowledge.EmbedText;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * 用 llama.cpp 的 /v1/embeddings 把文字變成向量。
 *
 * 跟 LlamaCppGenerateReplyAdapter 是同一個模子，但打的是**另一台** server：
 * 生成用的 qwen3:8b 不能兼差做 embedding —— 它沒被訓練成「向量距離 = 語意相似度」，
 * 而且為了一個向量跑一次 8B 推論貴得離譜。embedding 模型另外開一台：
 *
 *   llama-server -m bge-m3-Q8_0.gguf --embedding --port 8081
 *
 * 少了 --embedding 那個旗標，/v1/embeddings 不會開，這裡會收到 HTTP 錯誤。
 */
public final class LlamaCppEmbedText implements EmbedText {

    private static final String EMBEDDINGS_PATH = "/v1/embeddings";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // 比 chat 那邊的兩分鐘短得多：embedding 只跑一次 encoder，不像生成要逐 token 吐，
    // 正常是幾十毫秒的事。拖到 30 秒還沒回，那不是慢，是壞了
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI endpoint;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public LlamaCppEmbedText(URI baseUri) {
        this(baseUri, DEFAULT_REQUEST_TIMEOUT);
    }

    public LlamaCppEmbedText(URI baseUri, Duration requestTimeout) {
        Objects.requireNonNull(baseUri, "baseUri 不可為 null");
        this.endpoint = baseUri.resolve(EMBEDDINGS_PATH);
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout 不可為 null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public float[] embed(String text) {
        HttpResponse<String> response = send(EmbeddingRequest.body(text));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "llama.cpp 回應 HTTP " + response.statusCode() + "：" + preview(response.body()));
        }
        return EmbeddingResponse.vector(response.body());
    }

    private HttpResponse<String> send(String requestBody) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("呼叫 llama.cpp embedding 失敗：" + endpoint, e);
        } catch (InterruptedException e) {
            // 把中斷旗標補回去再丟，不然上層再也看不出這個執行緒被要求停下來
            Thread.currentThread().interrupt();
            throw new IllegalStateException("呼叫 llama.cpp embedding 時被中斷：" + endpoint, e);
        }
    }

    private static String preview(String body) {
        if (body == null) {
            return "(沒有內容)";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...(已截斷)";
    }
}
