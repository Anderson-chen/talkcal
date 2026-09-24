package eat.conversation.adapter.out.knowledge.llamacpp;

import eat.conversation.application.domain.model.Passage;
import eat.conversation.application.port.out.RetrievePassagesPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 用向量相似度檢索片段，向量由 llama.cpp 的 /v1/embeddings 算。
 *
 * 「怎麼算相關」在這裡的答案是「比意思像不像」，不是「比字面有沒有重疊」。
 * 於是「三文魚」配得上「鮭魚」（一個字都沒重疊），
 * 而「幾分鐘」也不會因為一個「分」字就撈到「營養成分」。
 *
 * 「文字怎麼變成向量」（HTTP、JSON）跟「拿到向量之後怎麼挑」（餘弦、兩道門檻）
 * 刻意放在同一個 class。下面那兩道門檻是對 bge-m3 量出來的，換一顆 embedding 模型，
 * 分數分布整個不一樣，門檻就得重量 —— 兩件事會一起變，所以住在一起。
 * 換供應商不是換掉半個 class，而是寫另一個 adapter，連同它自己量出來的門檻。
 *
 * embedding 跑在另一台 server：生成用的 qwen3:8b 不能兼差 ——
 * 它沒被訓練成「向量距離 = 語意相似度」，而且為了一個向量跑一次 8B 推論貴得離譜。
 *
 *   llama-server -m bge-m3-Q8_0.gguf --embedding --port 8081
 *
 * 少了 --embedding 那個旗標，/v1/embeddings 不會開，這裡會收到 HTTP 錯誤。
 *
 * core 那一側對這一切一無所知 —— 它只知道有個 RetrievePassagesPort。
 */
public final class LlamaCppRetrievePassagesAdapter implements RetrievePassagesPort {

    private static final String EMBEDDINGS_PATH = "/v1/embeddings";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // 比 chat 那邊的兩分鐘短得多：embedding 只跑一次 encoder，不像生成要逐 token 吐，
    // 正常是幾十毫秒的事。拖到 30 秒還沒回，那不是慢，是壞了
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private static final int TOP_K = 3;

    // 篩選用兩道關卡，缺一不可。
    //
    // 第一道是絕對地板：擋掉「這個問題跟知識庫根本沒關係」。
    // 對 bge-m3 加這份知識庫實測：不相干的問題最高只到 0.32，
    // 而相關但問得抽象的（「深海魚有什麼好處」）最低也有 0.50，中間空著 0.18。
    // 0.40 就取在那段空白的中間。
    //
    // 它不可以是負數：餘弦相似度可以是負的（方向相反），但那種片段一定不相關。
    // 更實際的理由是下面的相對門檻 —— best 若是負數，best * 0.9 反而「比 best 大」，
    // 整個比較就顛倒了。地板是正的，過得了地板的 best 就一定是正的，那個陷阱就不存在。
    private static final double SIMILARITY_FLOOR = 0.40;

    // 第二道是相對門檻：有訊號之後，只留下跟最相關那一段差距在 10% 以內的。
    //
    // 為什麼不能只用絕對門檻：分數的整排高度會隨問法浮動 —— 問「鮭魚要煎幾分鐘」，
    // 片段裡就寫著那些字，整排都在 0.6 以上；問「深海魚有什麼好處」一個字都對不上，
    // 整排掉到 0.5 以下。一條固定的橫線切不同高度的山，必然同時太鬆又太緊。
    //
    // 為什麼不能只用相對門檻：第一名永遠會留下。問「巴黎鐵塔」最高分 0.27 也照樣進 prompt，
    // 而餵爛資料比餵不到更糟 —— GroundedQuestion 那句「只依資料回答」會反咬一口，
    // 模型會拿著雞胸肉的資料說「參考資料中沒有提到巴黎鐵塔」。
    //
    // 這兩個數字是調參不是定理：目前只用 6 個問題對 6 段知識庫量過，樣本很小。
    // 知識庫長大、真的有好幾段同樣相關時，0.9 會太緊，要放寬。
    private static final double RELATIVE_THRESHOLD = 0.9;

    private final URI endpoint;
    private final HttpClient httpClient;
    private final List<Passage> knowledgeBase;

    // 索引出來的向量。null 代表「還沒索引」—— 只在 index() 的 synchronized 區塊裡讀寫，
    // 所以不需要 volatile。
    private List<Indexed> index;

    public LlamaCppRetrievePassagesAdapter(URI baseUri, List<Passage> knowledgeBase) {
        Objects.requireNonNull(baseUri, "baseUri 不可為 null");
        this.endpoint = baseUri.resolve(EMBEDDINGS_PATH);
        this.knowledgeBase = List.copyOf(Objects.requireNonNull(knowledgeBase, "knowledgeBase 不可為 null"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        // 建構子刻意不打任何一行網路。
        //
        // 這是為了讓兩個 outbound adapter 的啟動契約一致：LlamaCppGenerateReplyAdapter 的
        // 建構子也只組 URI、建 HttpClient，不連線。於是 embedding server 沒開跟 chat server
        // 沒開是同一種情況 —— 應用照樣啟動，等到有人提問才失敗、回 502，而不是一邊安靜啟動、
        // 另一邊連起都起不來。
    }

    @Override
    public List<Passage> retrievePassages(String question) {
        // 先確保索引在（第一次會真的去算），再算問題本身的向量
        List<Indexed> indexed = index();
        return select(embed(question), indexed, TOP_K, SIMILARITY_FLOOR, RELATIVE_THRESHOLD);
    }

    /**
     * 拿到向量之後怎麼挑：先過地板、由高到低排好，再過相對門檻、取前 topK 筆。
     *
     * 抽成 static 而且門檻從參數收，是為了讓這段純計算不必開 server 就能測 ——
     * 測試直接餵手寫的向量，不需要任何假物件。正式程式永遠只傳上面那幾個常數。
     */
    static List<Passage> select(float[] wanted, List<Indexed> indexed,
                                int topK, double similarityFloor, double relativeThreshold) {
        // 第一道關卡：過得了地板的，由高到低排好
        List<Scored> aboveFloor = indexed.stream()
                .map(entry -> new Scored(entry.passage(), cosineSimilarity(wanted, entry.vector())))
                .filter(scored -> scored.similarity() >= similarityFloor)
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed())
                .toList();
        if (aboveFloor.isEmpty()) {
            // 整個知識庫都跟這個問題無關。回空清單是正常結果（見 port 的契約），
            // core 收到空清單就會送出原始提問，讓模型自己回答
            return List.of();
        }

        // 第二道關卡：跟第一名差太多的不要。
        // 篩選與排序都是 adapter 的責任，core 收到的清單已經處理好
        double best = aboveFloor.getFirst().similarity();
        return aboveFloor.stream()
                .filter(scored -> scored.similarity() >= best * relativeThreshold)
                .limit(topK)
                .map(Scored::passage)
                .toList();
    }

    /**
     * 取得索引，第一次呼叫時才真的去算。
     *
     * 整個方法 synchronized，但代價可以忽略：沒有競爭的 synchronized 只是幾十奈秒，
     * 而且鎖只罩住「拿索引」這一下，底下的餘弦與排序都在鎖外面跑。
     * 少了它，兩個同時進來的請求會各自把整個知識庫算一遍。
     *
     * 算失敗時 index 維持 null，下一次提問會重試 ——
     * embedding server 晚一步開起來，應用自己會恢復，不必重啟。
     * 千萬別在失敗時存一份空索引：那樣 server 起來之後每一題都會安靜地檢索不到東西，
     * 沒有錯誤、沒有 502，只有模型不帶資料在回答。
     */
    private synchronized List<Indexed> index() {
        if (index == null) {
            index = knowledgeBase.stream()
                    // 出處跟內文一起算進向量：標題本身就帶著語意
                    //（「鮭魚.md > 營養成分」裡的「營養」），
                    // 這跟切段時把標題接回片段開頭是同一招，幾乎零成本
                    .map(passage -> new Indexed(passage, embed(passage.source() + "\n" + passage.text())))
                    .toList();
        }
        return index;
    }

    private float[] embed(String text) {
        HttpResponse<String> response = send(EmbeddingRequest.body(text));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "llama.cpp 回應 HTTP " + response.statusCode() + "：" + preview(response.body()));
        }
        return EmbeddingResponse.vector(response.body());
    }

    private HttpResponse<String> send(String requestBody) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
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

    /**
     * 餘弦相似度：比兩個向量的夾角，不比長度。
     *
     * 長度會被文字長短影響（同樣講鮭魚，400 字那段的向量就是比 20 字的長），
     * 方向才代表意思，所以分母要把長度除掉。
     * 這條公式在 2 維和 1024 維一模一樣，只是加總的項數不同。
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            // 索引和查詢打的是同一個端點，所以幾乎只有一個原因：
            // 索引算完之後，8081 那台被換成了另一顆模型。兩個向量空間完全不相通，
            // 算出來的數字沒有意義，所以寧可炸掉也不要回一個假答案
            throw new IllegalStateException("向量維度不一致（" + a.length + " vs " + b.length
                    + "）：索引與查詢必須用同一個 embedding 模型");
        }
        double dotProduct = 0;
        double squaredLengthA = 0;
        double squaredLengthB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += (double) a[i] * b[i];
            squaredLengthA += (double) a[i] * a[i];
            squaredLengthB += (double) b[i] * b[i];
        }
        if (squaredLengthA == 0 || squaredLengthB == 0) {
            // 零向量沒有方向，談不上夾角。回 0 當作「不相關」，也順便不會除以零
            return 0;
        }
        return dotProduct / (Math.sqrt(squaredLengthA) * Math.sqrt(squaredLengthB));
    }

    // package-private 只為了一件事：讓測試能直接組出索引餵給 select()
    record Indexed(Passage passage, float[] vector) {
    }

    private record Scored(Passage passage, double similarity) {
    }
}
