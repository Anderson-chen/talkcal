package eat.conversation.adapter.out.knowledge;

import eat.conversation.application.domain.model.Passage;
import eat.conversation.application.port.out.RetrievePassagesPort;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 用向量相似度檢索片段（RetrievePassagesPort 的第二個實作）。
 *
 * 跟 KeywordRetrievePassagesAdapter 比，差別只在「怎麼算相關」：
 * 前者比字面有沒有重疊，這裡比意思像不像。於是「三文魚」配得上「鮭魚」，
 * 而「幾分鐘」也不會再因為一個「分」字就撈到「營養成分」。
 *
 * core 那一側對這件事一無所知 —— 它只知道有個 RetrievePassagesPort。
 */
public final class EmbeddingRetrievePassagesAdapter implements RetrievePassagesPort {

    private static final int DEFAULT_TOP_K = 3;
    // 餘弦相似度的實際尺度：0.5 以上通常就算有關。
    // 這個數字跟關鍵字那版的 0.5 是兩種量綱，彼此不能比 ——
    // 正因為如此，門檻才必須留在 adapter，Passage 上也就不該有 score。
    private static final double DEFAULT_MINIMUM_SIMILARITY = 0.5;

    private final EmbedText embedText;
    private final List<Indexed> index;
    private final int topK;
    private final double minimumSimilarity;

    public EmbeddingRetrievePassagesAdapter(EmbedText embedText, List<Passage> knowledgeBase) {
        this(embedText, knowledgeBase, DEFAULT_TOP_K, DEFAULT_MINIMUM_SIMILARITY);
    }

    public EmbeddingRetrievePassagesAdapter(EmbedText embedText, List<Passage> knowledgeBase,
                                            int topK, double minimumSimilarity) {
        this.embedText = Objects.requireNonNull(embedText, "embedText 不可為 null");
        Objects.requireNonNull(knowledgeBase, "knowledgeBase 不可為 null");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必須大於 0");
        }
        this.topK = topK;
        this.minimumSimilarity = minimumSimilarity;
        // 建構時就把整個知識庫算成向量，這就是「離線索引」那一步。
        // 之後每次提問只要再算一次（問題本身），不必重算知識庫 ——
        // 知識庫有幾百段時，差別是每次提問打 1 次 embedding 還是 300 次。
        //
        // 代價是啟動時會打 N 次 HTTP：embedding server 沒開，應用就起不來。
        // 現在選擇這樣「早點炸」，而不是拖到第一個使用者提問才發現。
        this.index = knowledgeBase.stream()
                // 出處跟內文一起算進向量，理由與關鍵字那版相同：
                // 標題本身就帶著語意，而且這是切段時把標題接回片段的同一招
                .map(passage -> new Indexed(passage, embedText.embed(passage.source() + "\n" + passage.text())))
                .toList();
    }

    @Override
    public List<Passage> retrievePassages(String question) {
        float[] wanted = embedText.embed(question);
        return index.stream()
                .map(indexed -> new Scored(indexed.passage(), cosineSimilarity(wanted, indexed.vector())))
                // 篩選與排序都是 adapter 的責任，core 收到的清單已經處理好（見 port 的契約）
                .filter(scored -> scored.similarity() >= minimumSimilarity)
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed())
                .limit(topK)
                .map(Scored::passage)
                .toList();
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
            // 幾乎只有一個原因：索引和查詢用了不同的 embedding 模型。
            // 兩個空間完全不相通，算出來的數字沒有意義，所以寧可炸掉也不要回一個假答案
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

    private record Indexed(Passage passage, float[] vector) {
    }

    private record Scored(Passage passage, double similarity) {
    }
}
