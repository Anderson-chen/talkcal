package eat.conversation.adapter.out.knowledge;

import eat.conversation.application.domain.model.Passage;
import eat.conversation.application.port.out.RetrievePassagesPort;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用關鍵字比對檢索片段（RetrievePassagesPort 的第一個實作）。
 *
 * 它刻意不聰明。這一步要證明的不是檢索品質，是「檢索方式可以換」——
 * 之後換成 embedding，core 那一側（AskQuestionService、GroundedQuestion、Conversation）
 * 一個字都不用改，Application 也只換一個 @Bean。
 *
 * 比對方式：算「問題裡的字有多少比例出現在片段裡」。
 * 中文沒有空格，切詞得靠字典或模型，那都太重；用單字比對雖然粗糙，但零依賴、秒回、好懂。
 * 已知的兩個毛病，留給 embedding 那一版去修：
 * - 英文會被拆成單一字母（Omega → O,m,e,g,a），所以對英文幾乎無效。
 * - 同義詞完全不通：「三文魚」配不上「鮭魚」，一個字都沒重疊。
 */
public final class KeywordRetrievePassagesAdapter implements RetrievePassagesPort {

    // 最多回幾筆。取太多會塞爆 prompt、還會拿雜訊去分散模型注意力。
    private static final int DEFAULT_TOP_K = 3;
    // 低於這個比例就當作不相關。門檻留在 adapter，因為只有它知道自己這套分數的尺度 ——
    // 這正是 Passage 上沒有 score 的原因。
    private static final double DEFAULT_MINIMUM_SCORE = 0.5;

    private final List<Passage> knowledgeBase;
    private final int topK;
    private final double minimumScore;

    public KeywordRetrievePassagesAdapter(List<Passage> knowledgeBase) {
        this(knowledgeBase, DEFAULT_TOP_K, DEFAULT_MINIMUM_SCORE);
    }

    public KeywordRetrievePassagesAdapter(List<Passage> knowledgeBase, int topK, double minimumScore) {
        // 知識庫從建構子收，adapter 本身只管「怎麼找」，不管「找什麼」——
        // 所以它可以被任何一批片段測試，也不必為了換資料而改程式。
        this.knowledgeBase = List.copyOf(Objects.requireNonNull(knowledgeBase, "knowledgeBase 不可為 null"));
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必須大於 0");
        }
        this.topK = topK;
        this.minimumScore = minimumScore;
    }

    @Override
    public List<Passage> retrievePassages(String question) {
        Set<Integer> wanted = charactersOf(question);
        return knowledgeBase.stream()
                .map(passage -> new Scored(passage, score(passage, wanted)))
                // 先篩掉不夠相關的：port 的契約說好由 adapter 負責，core 沒有能力判斷。
                // 一個字都沒中的片段無條件不要 —— 就算門檻被設成 0 也一樣，
                // 「零交集」跟「分數偏低」是兩回事，前者不是調參能救的
                .filter(scored -> scored.score() > 0 && scored.score() >= minimumScore)
                // 再由高到低排序：Passage 上沒有 score，順序是 core 唯一拿得到的相關性資訊。
                // Stream.sorted 是穩定排序，同分的片段會維持知識庫裡的原順序
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::passage)
                .toList();
    }

    /**
     * 問題裡有多少比例的字出現在這個片段裡。
     */
    private static double score(Passage passage, Set<Integer> wanted) {
        // 空白提問（或整串都是標點）沒有任何字可比，一律不相關；
        // 這樣也不會除以零
        if (wanted.isEmpty()) {
            return 0;
        }
        // source 一起納入比對，不是只比 text：
        // 出處本身就帶著語意（「鮭魚.md > 營養成分」裡的「營養」），
        // 這跟切段時把標題接回片段開頭是同一招，幾乎零成本但很有效
        Set<Integer> haystack = charactersOf(passage.source() + passage.text());
        long hits = wanted.stream().filter(haystack::contains).count();
        return (double) hits / wanted.size();
    }

    /**
     * 取出可以用來比對的字：漢字與英數字，去重。
     * 標點與空白一律略過 —— 「，」「。」「？」在每個片段裡都有，放進來會讓所有東西都變得有點像。
     */
    private static Set<Integer> charactersOf(String text) {
        return text.codePoints()
                .filter(Character::isLetterOrDigit)
                .map(Character::toLowerCase)
                .boxed()
                // LinkedHashSet：去重，但保留出現順序，除錯時看起來比較直覺
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private record Scored(Passage passage, double score) {
    }
}
