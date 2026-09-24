package eat.conversation.adapter.out.knowledge.llamacpp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eat.conversation.adapter.out.knowledge.SampleKnowledgeBase;
import eat.conversation.adapter.out.knowledge.llamacpp.LlamaCppRetrievePassagesAdapter.Indexed;
import eat.conversation.application.domain.model.Passage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 三種測試，各守各的，沒有一種需要假物件：
 *
 * - select() 與 cosineSimilarity() 是純計算，直接餵手寫的向量。
 * - 「embedding server 還沒開」那組守啟動與恢復的契約，只需要一個沒人用的埠。
 * - 對真模型那組守檢索品質，需要 8081 真的在跑，所以標成 integration、預設不跑。
 *
 * 協定本身（送出什麼、怎麼讀回應）由 EmbeddingRequestTest、EmbeddingResponseTest 守，這裡不重複。
 */
@DisplayName("LlamaCppRetrievePassagesAdapter")
class LlamaCppRetrievePassagesAdapterTest {

    private static final Passage COOKING = new Passage("中火煎四分鐘再翻面。", "鮭魚.md > 烹調建議");
    private static final Passage STORAGE = new Passage("冷藏於 0 到 4 度。", "鮭魚.md > 保存方式");
    private static final Passage AVOCADO = new Passage("膳食纖維含量高。", "酪梨.md > 營養成分");

    private static final List<Passage> KNOWLEDGE_BASE = List.of(COOKING, STORAGE, AVOCADO);

    // 用 3 維向量就夠驗了 —— 餘弦公式在 3 維和 1024 維一模一樣，只差加總的項數。
    // 好處是每個向量的方向一眼看得出來，對 QUESTION 的相似度是：
    //   COOKING (1,0,0) 同向            → 1.0
    //   STORAGE (0.8,0.6,0) 夾角較大     → 0.8
    //   AVOCADO (0,1,0) 垂直            → 0.0
    // 第三個維度只為了 UNRELATED_QUESTION 而存在：它跟三段知識全部垂直，分數一律 0，
    // 這樣才寫得出「整個知識庫都在地板之下」那個情境。
    private static final float[] QUESTION = {1, 0, 0};
    private static final float[] UNRELATED_QUESTION = {0, 0, 1};

    // 索引刻意不照相似度的順序擺：照順序擺的話，就算 select() 完全沒排序，
    // 「夾角越小排越前面」那題也會碰巧通過
    private static final List<Indexed> INDEX = List.of(
            new Indexed(AVOCADO, new float[] {0, 1, 0}),
            new Indexed(COOKING, new float[] {1, 0, 0}),
            new Indexed(STORAGE, new float[] {0.8f, 0.6f, 0}));

    // relativeThreshold 傳 0 等於關掉相對門檻，讓那些只關心排序／topK 的測試不受它干擾
    private static List<Passage> select(float[] question, int topK, double floor, double relative) {
        return LlamaCppRetrievePassagesAdapter.select(question, INDEX, topK, floor, relative);
    }

    @Nested
    @DisplayName("依相似度挑片段")
    class Ranking {

        @Test
        @DisplayName("夾角越小排越前面")
        void sortsBySimilarity() {
            assertEquals(List.of(COOKING, STORAGE, AVOCADO), select(QUESTION, 3, 0, 0));
        }

        @Test
        @DisplayName("低於地板的被篩掉")
        void filtersBelowFloor() {
            assertEquals(List.of(COOKING, STORAGE), select(QUESTION, 3, 0.5, 0));
        }

        @Test
        @DisplayName("最多只回 topK 筆")
        void limitsToTopK() {
            assertEquals(List.of(COOKING), select(QUESTION, 1, 0, 0));
        }
    }

    /**
     * 兩道關卡各擋各的，缺一不可：
     * 地板擋「整個知識庫都跟這題無關」，相對門檻擋「過得了地板但比第一名差太多」。
     */
    @Nested
    @DisplayName("地板與相對門檻")
    class TwoGates {

        @Test
        @DisplayName("相對門檻 0.9：跟第一名差超過一成的不要")
        void relativeThresholdKeepsOnlyTheClosest() {
            // COOKING 1.0、STORAGE 0.8。0.8 < 1.0 * 0.9，所以 STORAGE 被濾掉 ——
            // 它過得了地板，但跟最相關那一段差太多
            assertEquals(List.of(COOKING), select(QUESTION, 3, 0, 0.9));
        }

        @Test
        @DisplayName("相對門檻放寬到 0.75，第二名就留得住")
        void looserRelativeThresholdKeepsMore() {
            assertEquals(List.of(COOKING, STORAGE), select(QUESTION, 3, 0, 0.75));
        }

        @Test
        @DisplayName("整批都在地板之下時回空清單：相對門檻不會把第一名救回來")
        void floorIsAppliedBeforeRelativeThreshold() {
            // UNRELATED_QUESTION 跟三段知識全部垂直，分數一律 0。
            // 若少了地板、只留相對門檻，第一名永遠會留下 ——
            // 於是問「巴黎鐵塔」也會硬塞一段雞胸肉進 prompt，那比什麼都不給更糟
            assertEquals(List.of(), select(UNRELATED_QUESTION, 3, 0.4, 0.9));
        }
    }

    @Nested
    @DisplayName("餘弦相似度")
    class Cosine {

        @Test
        @DisplayName("同向是 1、垂直是 0、反向是 -1")
        void measuresAngle() {
            assertEquals(1, LlamaCppRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {1, 0}), 1e-9);
            assertEquals(0, LlamaCppRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {0, 1}), 1e-9);
            assertEquals(-1, LlamaCppRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {-1, 0}), 1e-9);
        }

        @Test
        @DisplayName("只看方向不看長度：把向量拉長十倍，相似度不變")
        void ignoresLength() {
            // 用 3-4-5 這組：整數在 float 裡是精確的，長度 5 和 50 也開得出整數平方根，
            // 兩邊都算得出剛好的 0.6，不必靠寬鬆的容許誤差把話含混過去
            assertEquals(0.6, LlamaCppRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {3, 4}), 1e-9);
            assertEquals(0.6, LlamaCppRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {10, 0}, new float[] {30, 40}), 1e-9);
        }

        @Test
        @DisplayName("零向量沒有方向，當作不相關而不是除以零")
        void handlesZeroVector() {
            assertEquals(0, LlamaCppRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {0, 0}, new float[] {1, 0}), 1e-9);
        }

        @Test
        @DisplayName("維度不一致時炸掉：那代表索引和查詢用了不同的模型")
        void rejectsDimensionMismatch() {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> LlamaCppRetrievePassagesAdapter.cosineSimilarity(
                            new float[] {1, 0}, new float[] {1, 0, 0}));

            assertTrue(thrown.getMessage().contains("同一個 embedding 模型"), thrown.getMessage());
        }
    }

    /**
     * 啟動順序不該是個需要人記得的規矩：8081 比應用晚開，應用也要能自己恢復。
     *
     * 這組不標 integration —— 它需要的「外部環境」只是一個沒人在聽的埠，哪台機器都有。
     *
     * 恢復那題非得讓 server「從沒開變成開了」不可。只讓它一直不開、連問兩次是抓不到壞法的：
     * 就算索引失敗時被存成空的，第二次提問算「問題本身」的向量時照樣連不上、照樣丟例外，
     * 測試一樣綠。那種壞法只在 server 起來之後才現形 —— 從此每題都安靜地回空清單。
     */
    @Nested
    @DisplayName("embedding server 還沒開")
    class ServerNotUpYet {

        private static final String ANY_QUESTION = "鮭魚要煎幾分鐘？";

        @Test
        @DisplayName("建構時一行網路都不打：應用照樣啟動得了")
        void doesNotConnectAtConstruction() throws IOException {
            URI nobodyListening = uriOf(freePort());

            // 建構子若偷偷先把知識庫索引起來，這裡就會丟出「呼叫 llama.cpp embedding 失敗」。
            // 守的是兩個 outbound adapter 的啟動契約一致：LlamaCppGenerateReplyAdapter 也不在建構時連線，
            // 於是 8081 沒開跟 8080 沒開是同一種情況 —— 都在提問時才失敗
            assertDoesNotThrow(() -> new LlamaCppRetrievePassagesAdapter(nobodyListening, KNOWLEDGE_BASE));
        }

        @Test
        @DisplayName("第一題失敗，server 起來之後自己恢復，不必重啟應用")
        void recoversOnceServerComesUp() throws IOException {
            int port = freePort();
            LlamaCppRetrievePassagesAdapter adapter =
                    new LlamaCppRetrievePassagesAdapter(uriOf(port), KNOWLEDGE_BASE);

            assertThrows(IllegalStateException.class, () -> adapter.retrievePassages(ANY_QUESTION));

            HttpServer server = startEmbeddingServerOn(port);
            try {
                // 每段、每題回的都是同一個向量，分數全是 1，三段都該回來。
                // 若第一次失敗時被存成空索引，這裡會拿到空清單 —— 沒有錯誤、沒有 502，
                // 只是從此檢索不到東西，模型不帶資料在回答
                assertEquals(KNOWLEDGE_BASE, adapter.retrievePassages(ANY_QUESTION));
            } finally {
                server.stop(0);
            }
        }

        // 向 OS 要一個當下沒人用的埠再立刻放掉：比寫死某個「應該沒人用」的埠可靠
        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
                return socket.getLocalPort();
            }
        }

        private static URI uriOf(int port) {
            return URI.create("http://127.0.0.1:" + port);
        }

        /**
         * 只為「server 晚一步開起來」這個情境存在的最小 server：不管收到什麼都回同一個向量。
         *
         * 它不驗協定，所以專案不架假 server 的那個理由（對協定的誤解會同時寫進 adapter 和假 server）
         * 在這裡不成立 —— 這題要的只是「剛才連不上的那個埠，現在有人接了」。
         * 協定對不對，由 EmbeddingResponseTest 和下面對真模型那組守著。
         */
        private static HttpServer startEmbeddingServerOn(int port) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/v1/embeddings", exchange -> {
                // 先把 request body 讀完再回應，連線才能乾淨地收尾或重用
                exchange.getRequestBody().readAllBytes();
                byte[] body = "{\"data\":[{\"embedding\":[1,0,0]}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        }
    }

    /**
     * 檢索品質：對真的 embedding server、真的知識庫、正式用的地板與相對門檻。
     *
     * 上面那些手寫向量的測試驗的是「拿到向量之後怎麼挑」—— 排序、兩道關卡、topK，
     * 那些是純邏輯，手寫向量就守得完。這一組驗的是只有真模型才答得出來的問題：
     * 那兩個門檻，對真實的問法到底合不合用。
     *
     * 所以這裡不架假 server，理由跟 LlamaCppGenerateReplyAdapterTest 寫的一樣：
     * 假 server 回的向量是我們自己編的，編出來的當然「語意正確」——
     * 那只證明我們會編數字。
     *
     * 順帶也守住了「文字變向量」那一段跟真實世界的往返：
     * 中文送不過去（UTF-8 壞掉）、每次回的維度不一樣（cosineSimilarity 會直接炸）、
     * 向量沒抓到語意（三文魚那題），這幾種壞法在這裡都會紅。
     *
     * 這六題就是當初用來訂那兩個數字的量測樣本。把它們留成測試，
     * 之後有人調門檻、換 embedding 模型、或改知識庫內容，就會被擋下來 ——
     * 那兩個數字是調參不是定理，沒有測試守著，調壞了不會有人發現。
     *
     * 實測到的分數分布（bge-m3 加這份知識庫）：
     *   不相干的問題    0.27 ~ 0.32
     *   相關但問得抽象  0.50 ~ 0.56
     *   問得具體        0.76 ~ 0.79
     * 地板 0.40 取在前兩段中間那片空白，相對門檻 0.9 負責在有訊號時擋掉
     * 「同一份文件但不同主題」的段落。
     *
     * 標籤下在 @Nested 上，所以預設的 ./gradlew test 只會排除這一組，
     * 同一個檔案裡其他不需要 8081 的測試照跑。
     */
    @Nested
    @Tag("integration")
    @DisplayName("對真模型的檢索品質")
    class RetrievalQuality {

        // 跟正式程式讀同一個系統屬性，才不會一邊指到別台、一邊還在檢查本機那台
        private static final URI BASE_URI =
                URI.create(System.getProperty("llamacpp.embeddingBaseUri", "http://127.0.0.1:8081"));

        @BeforeAll
        static void requireRunningServer() {
            // 跟 LlamaCppGenerateReplyAdapterTest 同一套：server 沒開時整組「跳過」而不是「失敗」——
            // 環境沒準備好不等於程式壞了，這兩件事必須分得開，
            // 否則紅燈很快就會被當成背景雜訊。
            // 用 @BeforeAll 而不是 @BeforeEach：健康檢查一次就夠，不必每題打一次
            Assumptions.assumeTrue(isHealthy(),
                    () -> "embedding server 沒有在 " + BASE_URI + " 執行，跳過檢索品質測試");
        }

        private LlamaCppRetrievePassagesAdapter retrieval() {
            return new LlamaCppRetrievePassagesAdapter(BASE_URI, SampleKnowledgeBase.passages());
        }

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                // 問得具體，用詞跟片段幾乎重疊
                "鮭魚要煎幾分鐘？,         鮭魚.md > 烹調建議",
                "雞胸肉有多少蛋白質？,     雞胸肉.md > 營養成分",
                // 同義詞：「三文魚」跟「鮭魚」一個字都沒重疊，關鍵字比對得分是 0
                "三文魚有什麼營養？,       鮭魚.md > 營養成分",
                // 抽象問法：一個字都對不上，整排分數掉到 0.5 以下。
                // 這題正是「絕對門檻 0.5」會誤殺、換成地板 0.40 才救得回來的那一題
                "深海魚對身體有什麼好處？, 鮭魚.md > 營養成分",
        })
        @DisplayName("撈得到，而且第一段是對的")
        void retrievesTheRightPassage(String question, String expectedSource) {
            List<Passage> found = retrieval().retrievePassages(question);

            assertTrue(!found.isEmpty(), "什麼都沒檢索到：" + question);
            assertEquals(expectedSource, found.get(0).source(),
                    "最相關的那段不對，實際撈到：" + found);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"巴黎鐵塔有多高？", "如何學習 Java？"})
        @DisplayName("知識庫沒有的東西：回空清單，不硬塞不相干的片段")
        void returnsNothingForUnrelatedQuestion(String question) {
            // 硬塞比不給更糟：GroundedQuestion 那句「只依資料回答」會反咬一口，
            // 模型會拿著雞胸肉的資料說「參考資料中沒有提到巴黎鐵塔」
            assertEquals(List.of(), retrieval().retrievePassages(question));
        }

        private static boolean isHealthy() {
            try {
                HttpRequest request = HttpRequest.newBuilder(BASE_URI.resolve("/health"))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200;
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
