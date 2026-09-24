package eat.conversation.adapter.out.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.conversation.adapter.out.knowledge.llamacpp.LlamaCppEmbedText;
import eat.conversation.application.domain.model.Passage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("EmbeddingRetrievePassagesAdapter")
class EmbeddingRetrievePassagesAdapterTest {

    private static final Passage COOKING = new Passage("中火煎四分鐘再翻面。", "鮭魚.md > 烹調建議");
    private static final Passage STORAGE = new Passage("冷藏於 0 到 4 度。", "鮭魚.md > 保存方式");
    private static final Passage AVOCADO = new Passage("膳食纖維含量高。", "酪梨.md > 營養成分");

    private static final List<Passage> KNOWLEDGE_BASE = List.of(COOKING, STORAGE, AVOCADO);

    private static final String QUESTION = "鮭魚要煎幾分鐘？";
    private static final String UNRELATED_QUESTION = "巴黎鐵塔有多高？";

    // 用 3 維向量就夠驗了 —— 餘弦公式在 3 維和 1024 維一模一樣，只差加總的項數。
    // 好處是每個向量的方向一眼看得出來，對「問題」的相似度是：
    //   COOKING (1,0,0) 同向            → 1.0
    //   STORAGE (0.8,0.6,0) 夾角較大     → 0.8
    //   AVOCADO (0,1,0) 垂直            → 0.0
    // 第三個維度只為了「不相干問題」而存在：它跟三段知識全部垂直，分數一律 0，
    // 這樣才寫得出「整個知識庫都在地板之下」那個情境。
    private static final Map<String, float[]> VECTORS = Map.of(
            QUESTION, new float[] {1, 0, 0},
            UNRELATED_QUESTION, new float[] {0, 0, 1},
            indexKeyOf(COOKING), new float[] {1, 0, 0},
            indexKeyOf(STORAGE), new float[] {0.8f, 0.6f, 0},
            indexKeyOf(AVOCADO), new float[] {0, 1, 0});

    // adapter 索引時把出處和內文接在一起才送去算向量，這裡照同一個規則組 key
    private static String indexKeyOf(Passage passage) {
        return passage.source() + "\n" + passage.text();
    }

    // 假 embedding：查表就回，不必開 server。
    // 這正是 EmbedText 這個介面存在的理由 —— 排序邏輯的測試不該依賴一台跑著模型的機器
    private record FakeEmbedText(List<String> calls) implements EmbedText {
        FakeEmbedText() {
            this(new ArrayList<>());
        }

        @Override
        public float[] embed(String text) {
            calls.add(text);
            float[] vector = VECTORS.get(text);
            if (vector == null) {
                throw new IllegalStateException("測試沒有替這段文字準備向量：" + text);
            }
            return vector;
        }
    }

    // 第一次呼叫就炸（模擬 embedding server 還沒開），之後恢復正常
    private static final class FailingOnceEmbedText implements EmbedText {
        private boolean failed;

        @Override
        public float[] embed(String text) {
            if (!failed) {
                failed = true;
                throw new IllegalStateException("呼叫 llama.cpp embedding 失敗");
            }
            return new FakeEmbedText().embed(text);
        }
    }

    // relativeThreshold 傳 0 等於關掉相對門檻，讓那些只關心排序／topK 的測試不受它干擾
    private static EmbeddingRetrievePassagesAdapter adapter(int topK, double floor, double relative) {
        return new EmbeddingRetrievePassagesAdapter(new FakeEmbedText(), KNOWLEDGE_BASE, topK, floor, relative);
    }

    @Nested
    @DisplayName("依相似度挑片段")
    class Ranking {

        @Test
        @DisplayName("夾角越小排越前面")
        void sortsBySimilarity() {
            assertEquals(List.of(COOKING, STORAGE, AVOCADO), adapter(3, 0, 0).retrievePassages(QUESTION));
        }

        @Test
        @DisplayName("低於地板的被篩掉")
        void filtersBelowFloor() {
            assertEquals(List.of(COOKING, STORAGE), adapter(3, 0.5, 0).retrievePassages(QUESTION));
        }

        @Test
        @DisplayName("最多只回 topK 筆")
        void limitsToTopK() {
            assertEquals(List.of(COOKING), adapter(1, 0, 0).retrievePassages(QUESTION));
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
            // COOKING 1.0、STORAGE 0.8。0.8 < 1.0 * 0.9，所以STORAGE被濾掉 ——
            // 它過得了地板，但跟最相關那一段差太多
            assertEquals(List.of(COOKING), adapter(3, 0, 0.9).retrievePassages(QUESTION));
        }

        @Test
        @DisplayName("相對門檻放寬到 0.75，第二名就留得住")
        void looserRelativeThresholdKeepsMore() {
            assertEquals(List.of(COOKING, STORAGE), adapter(3, 0, 0.75).retrievePassages(QUESTION));
        }

        @Test
        @DisplayName("整批都在地板之下時回空清單：相對門檻不會把第一名救回來")
        void floorIsAppliedBeforeRelativeThreshold() {
            // UNRELATED_QUESTION跟三段知識全部垂直，分數一律 0。
            // 若少了地板、只留相對門檻，第一名永遠會留下 ——
            // 於是問「巴黎鐵塔」也會硬塞一段雞胸肉進 prompt，那比什麼都不給更糟
            assertEquals(List.of(), adapter(3, 0.4, 0.9).retrievePassages(UNRELATED_QUESTION));
        }
    }

    @Nested
    @DisplayName("建構時就擋下來")
    class Construction {

        @Test
        @DisplayName("topK 不是正數時拒絕：回零筆片段沒有意義")
        void rejectNonPositiveTopK() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EmbeddingRetrievePassagesAdapter(new FakeEmbedText(), KNOWLEDGE_BASE, 0, 0.4, 0.9));
        }

        @Test
        @DisplayName("地板是負數時拒絕：那會讓相對門檻的比較顛倒過來")
        void rejectNegativeFloor() {
            // best 若是負數，best * 0.9 反而比 best 大，整個比較就反了。
            // 把負數擋在門外，那個陷阱就不存在
            assertThrows(IllegalArgumentException.class,
                    () -> new EmbeddingRetrievePassagesAdapter(new FakeEmbedText(), KNOWLEDGE_BASE, 3, -0.1, 0.9));
        }

        @Test
        @DisplayName("相對門檻超出 0 到 1 時拒絕")
        void rejectRelativeThresholdOutOfRange() {
            assertThrows(IllegalArgumentException.class,
                    () -> new EmbeddingRetrievePassagesAdapter(new FakeEmbedText(), KNOWLEDGE_BASE, 3, 0.4, 1.5));
        }
    }

    @Nested
    @DisplayName("索引是第一次用到才做的")
    class Indexing {

        @Test
        @DisplayName("建構時一行網路都不打：embedding server 沒開，應用照樣啟動得了")
        void doesNotEmbedAtConstruction() {
            FakeEmbedText embedText = new FakeEmbedText();

            new EmbeddingRetrievePassagesAdapter(embedText, KNOWLEDGE_BASE, 3, 0, 0);

            // 這一題守的是兩個 outbound adapter 的啟動契約一致：
            // LlamaCppGenerateReplyAdapter 的建構子也不連線，
            // 於是 8081 沒開跟 8080 沒開是同一種情況 —— 都在提問時才失敗
            assertEquals(List.of(), embedText.calls(), "建構子不該呼叫 embedding");
        }

        @Test
        @DisplayName("第一次提問時索引一次，之後每次提問只多算問題本身那一次")
        void indexesOnceOnFirstUse() {
            FakeEmbedText embedText = new FakeEmbedText();
            EmbeddingRetrievePassagesAdapter adapter =
                    new EmbeddingRetrievePassagesAdapter(embedText, KNOWLEDGE_BASE, 3, 0, 0);

            adapter.retrievePassages(QUESTION);
            assertEquals(4, embedText.calls().size(), "第一次提問要算 3 段知識 + 1 個問題");

            adapter.retrievePassages(QUESTION);

            // 第二次只多算問題那一次；若知識庫每次都重算，這裡會是 4 + 4 = 8
            assertEquals(5, embedText.calls().size(), "知識庫不該在每次提問時重算");
        }

        @Test
        @DisplayName("索引失敗後下一次提問會重試：server 晚一步開起來，應用自己會恢復")
        void retriesAfterFailedIndexing() {
            FailingOnceEmbedText embedText = new FailingOnceEmbedText();
            EmbeddingRetrievePassagesAdapter adapter =
                    new EmbeddingRetrievePassagesAdapter(embedText, KNOWLEDGE_BASE, 3, 0, 0);

            assertThrows(IllegalStateException.class, () -> adapter.retrievePassages(QUESTION));

            // 沒有把「失敗」記起來當成索引，所以第二次會重新算，這次就成功了
            assertEquals(List.of(COOKING, STORAGE, AVOCADO), adapter.retrievePassages(QUESTION));
        }
    }

    @Nested
    @DisplayName("餘弦相似度")
    class Cosine {

        @Test
        @DisplayName("同向是 1、垂直是 0、反向是 -1")
        void measuresAngle() {
            assertEquals(1, EmbeddingRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {1, 0}), 1e-9);
            assertEquals(0, EmbeddingRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {0, 1}), 1e-9);
            assertEquals(-1, EmbeddingRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {-1, 0}), 1e-9);
        }

        @Test
        @DisplayName("只看方向不看長度：把向量拉長十倍，相似度不變")
        void ignoresLength() {
            // 用 3-4-5 這組：整數在 float 裡是精確的，長度 5 和 50 也開得出整數平方根，
            // 兩邊都算得出剛好的 0.6，不必靠寬鬆的容許誤差把話含混過去
            assertEquals(0.6, EmbeddingRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {1, 0}, new float[] {3, 4}), 1e-9);
            assertEquals(0.6, EmbeddingRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {10, 0}, new float[] {30, 40}), 1e-9);
        }

        @Test
        @DisplayName("零向量沒有方向，當作不相關而不是除以零")
        void handlesZeroVector() {
            assertEquals(0, EmbeddingRetrievePassagesAdapter.cosineSimilarity(
                    new float[] {0, 0}, new float[] {1, 0}), 1e-9);
        }

        @Test
        @DisplayName("維度不一致時炸掉：那代表索引和查詢用了不同的模型")
        void rejectsDimensionMismatch() {
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> EmbeddingRetrievePassagesAdapter.cosineSimilarity(
                            new float[] {1, 0}, new float[] {1, 0, 0}));

            assertTrue(thrown.getMessage().contains("同一個 embedding 模型"), thrown.getMessage());
        }
    }

    /**
     * 檢索品質：對真的 embedding server、真的知識庫、預設的地板與相對門檻。
     *
     * 上面那些用假 EmbedText 的測試驗的是「拿到向量之後怎麼挑」——
     * 排序、兩道關卡、topK、索引時機，那些是純邏輯，手寫向量就守得完。
     * 這一組驗的是只有真模型才答得出來的問題：預設那兩個門檻，對真實的問法到底合不合用。
     *
     * 所以這裡不架假 server，理由跟 LlamaCppGenerateReplyAdapterTest 寫的一樣：
     * 假 server 回的向量是我們自己編的，編出來的當然「語意正確」——
     * 那只證明我們會編數字。真正要問的是 bge-m3 算出來的向量，
     * 配上我們訂的門檻，對真實問法到底管不管用；那個問題只有真模型答得出來。
     *
     * 這六題就是當初用來訂那兩個數字的量測樣本。把它們留成測試，
     * 之後有人調門檻、換 embedding 模型、或改知識庫內容，就會被擋下來——
     * 那兩個數字是調參不是定理，沒有測試守著，調壞了不會有人發現。
     *
     * 實測到的分數分布（bge-m3 加這份知識庫）：
     *   不相干的問題    0.27 ~ 0.32
     *   相關但問得抽象  0.50 ~ 0.56
     *   問得具體        0.76 ~ 0.79
     * 地板 0.40 取在前兩段中間那片空白，相對門檻 0.9 負責在有訊號時擋掉
     * 「同一份文件但不同主題」的段落。
     *
     * 標上 integration 而不是另開一個檔案：它測的還是這個 adapter，只是需要外部環境。
     * 標籤下在 @Nested 上，所以預設的 ./gradlew test 只會排除這一組，
     * 同一個檔案裡那些純邏輯的測試照跑。
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

        private EmbeddingRetrievePassagesAdapter retrieval() {
            // 用預設的 topK／地板／相對門檻 —— 這一組測的就是預設值合不合用
            return new EmbeddingRetrievePassagesAdapter(
                    new LlamaCppEmbedText(BASE_URI), SampleKnowledgeBase.passages());
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
