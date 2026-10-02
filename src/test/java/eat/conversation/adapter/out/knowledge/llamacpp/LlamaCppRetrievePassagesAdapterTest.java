package eat.conversation.adapter.out.knowledge.llamacpp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eat.conversation.adapter.out.knowledge.llamacpp.LlamaCppRetrievePassagesAdapter.Indexed;
import eat.conversation.application.domain.model.Passage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * 兩種測試，各守各的，沒有一種需要假物件：
 *
 * - select() 與 cosineSimilarity() 是純計算，直接餵手寫的向量。
 * - 「embedding server 還沒開」那組守啟動與恢復的契約，只需要一個沒人用的埠。
 *
 * 對真模型的檢索品質需要 8081 真的在跑，放在 src/integrationTest 的 RetrievalQualityTest，這裡不跑。
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
     * 這組留在單元測試這邊 —— 它需要的「外部環境」只是一個沒人在聽的埠，哪台機器都有。
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
            RestClient nobodyListening = clientFor(freePort());

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
                    new LlamaCppRetrievePassagesAdapter(clientFor(port), KNOWLEDGE_BASE);

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

        // 正式環境的 client 由組裝根從 Spring Boot 的 Builder 建（帶觀測、逾時）；
        // 這裡只需要「指到那個埠」，用最陽春的 RestClient 就夠
        private static RestClient clientFor(int port) {
            return RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        }

        /**
         * 只為「server 晚一步開起來」這個情境存在的最小 server：不管收到什麼都回同一個向量。
         *
         * 它不驗協定，所以專案不架假 server 的那個理由（對協定的誤解會同時寫進 adapter 和假 server）
         * 在這裡不成立 —— 這題要的只是「剛才連不上的那個埠，現在有人接了」。
         * 協定對不對，由 EmbeddingResponseTest 和對真模型的 RetrievalQualityTest 守著。
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
}
