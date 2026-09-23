package eat.conversation.adapter.out.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.conversation.application.domain.model.Passage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EmbeddingRetrievePassagesAdapter")
class EmbeddingRetrievePassagesAdapterTest {

    private static final Passage 烹調 = new Passage("中火煎四分鐘再翻面。", "鮭魚.md > 烹調建議");
    private static final Passage 保存 = new Passage("冷藏於 0 到 4 度。", "鮭魚.md > 保存方式");
    private static final Passage 酪梨 = new Passage("膳食纖維含量高。", "酪梨.md > 營養成分");

    private static final List<Passage> 知識庫 = List.of(烹調, 保存, 酪梨);

    private static final String 問題 = "鮭魚要煎幾分鐘？";

    // 用 2 維向量就夠驗餘弦了 —— 公式在 2 維和 1024 維一模一樣，只差加總的項數。
    // 好處是每個向量的方向一眼看得出來：
    //   問題 (1,0) 與 烹調 (1,0) 同向 → 1.0
    //   保存 (0.8,0.6) 夾角較大        → 0.8
    //   酪梨 (0,1) 垂直，完全不相干     → 0.0
    private static final Map<String, float[]> 向量 = Map.of(
            問題, new float[] {1, 0},
            indexKeyOf(烹調), new float[] {1, 0},
            indexKeyOf(保存), new float[] {0.8f, 0.6f},
            indexKeyOf(酪梨), new float[] {0, 1});

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
            float[] vector = 向量.get(text);
            if (vector == null) {
                throw new IllegalStateException("測試沒有替這段文字準備向量：" + text);
            }
            return vector;
        }
    }

    private static EmbeddingRetrievePassagesAdapter adapter(int topK, double minimumSimilarity) {
        return new EmbeddingRetrievePassagesAdapter(new FakeEmbedText(), 知識庫, topK, minimumSimilarity);
    }

    @Nested
    @DisplayName("依相似度挑片段")
    class Ranking {

        @Test
        @DisplayName("夾角越小排越前面")
        void sortsBySimilarity() {
            assertEquals(List.of(烹調, 保存, 酪梨), adapter(3, 0).retrievePassages(問題));
        }

        @Test
        @DisplayName("不夠像的被門檻篩掉")
        void filtersBelowThreshold() {
            assertEquals(List.of(烹調, 保存), adapter(3, 0.5).retrievePassages(問題));
        }

        @Test
        @DisplayName("最多只回 topK 筆")
        void limitsToTopK() {
            assertEquals(List.of(烹調), adapter(1, 0).retrievePassages(問題));
        }
    }

    @Nested
    @DisplayName("索引是建構時就做好的")
    class Indexing {

        @Test
        @DisplayName("知識庫在建構時算一次，之後每次提問只多算問題本身那一次")
        void indexesOnceAtConstruction() {
            FakeEmbedText embedText = new FakeEmbedText();

            EmbeddingRetrievePassagesAdapter adapter =
                    new EmbeddingRetrievePassagesAdapter(embedText, 知識庫, 3, 0);
            assertEquals(3, embedText.calls().size(), "建構時應該把三段知識各算一次");

            adapter.retrievePassages(問題);
            adapter.retrievePassages(問題);

            // 3 段知識 + 2 次提問 = 5；若知識庫每次提問都重算，這裡會是 3 + 3*2 = 9
            assertEquals(5, embedText.calls().size(), "知識庫不該在每次提問時重算");
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
}
