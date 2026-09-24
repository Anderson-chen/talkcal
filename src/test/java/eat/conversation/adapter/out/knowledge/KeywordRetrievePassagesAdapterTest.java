package eat.conversation.adapter.out.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eat.conversation.application.domain.model.Passage;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("KeywordRetrievePassagesAdapter")
class KeywordRetrievePassagesAdapterTest {

    private static final Passage COOKING = new Passage(
            "香煎鮭魚先用廚房紙巾吸乾表面水分，皮朝下入鍋，中火煎四分鐘再翻面。", "鮭魚.md > 烹調建議");
    private static final Passage STORAGE = new Passage(
            "新鮮鮭魚需冷藏於 0 到 4 度，兩天內食用完畢。", "鮭魚.md > 保存方式");
    private static final Passage AVOCADO = new Passage(
            "酪梨的脂肪以單元不飽和脂肪酸為主，膳食纖維含量高。", "酪梨.md > 營養成分");

    private static final List<Passage> KNOWLEDGE_BASE = List.of(COOKING, STORAGE, AVOCADO);

    // 門檻放到 0，讓「排序」「取幾筆」這些行為不被篩選干擾
    private static KeywordRetrievePassagesAdapter adapter(int topK) {
        return new KeywordRetrievePassagesAdapter(KNOWLEDGE_BASE, topK, 0);
    }

    @Nested
    @DisplayName("找得到的時候")
    class Hits {

        @Test
        @DisplayName("命中最多字的片段排在最前面")
        void sortsByRelevance() {
            List<Passage> found = adapter(3).retrievePassages("鮭魚要煎幾分鐘？");

            // COOKING 5/7（鮭魚煎分鐘）、STORAGE 2/7（鮭魚）、AVOCADO 1/7 ——
            // AVOCADO會上榜是因為出處「營養成分」裡有個「分」字撞上了「幾分鐘」的「分」。
            // 這就是關鍵字比對的典型毛病：字面撞對了，意思八竿子打不著。
            // 預設門檻 0.5 會把它擋掉，但擋掉的理由是分數低，不是因為它聽得懂中文
            assertEquals(List.of(COOKING, STORAGE, AVOCADO), found);
        }

        @Test
        @DisplayName("最多只回 topK 筆，免得塞爆 prompt")
        void limitsToTopK() {
            List<Passage> found = adapter(1).retrievePassages("鮭魚要煎幾分鐘？");

            assertEquals(List.of(COOKING), found);
        }

        @Test
        @DisplayName("出處也算數：問「營養」會配上標題裡有營養的片段，即使內文沒出現這兩個字")
        void matchesAgainstSourceToo() {
            List<Passage> found =
                    // 門檻 0.8 是刻意的：把出處一起算進去是 5/5，只比內文只有 3/5（營養兩個字只在標題裡）。
                    // 門檻卡在中間，這題才真的驗得到「出處有被算進去」
                    new KeywordRetrievePassagesAdapter(KNOWLEDGE_BASE, 3, 0.8).retrievePassages("酪梨的營養？");

            assertEquals(List.of(AVOCADO), found);
        }
    }

    @Nested
    @DisplayName("找不到的時候")
    class Misses {

        @Test
        @DisplayName("不夠相關的一律篩掉，是 adapter 的責任不是 core 的")
        void filtersBelowThreshold() {
            KeywordRetrievePassagesAdapter strict = new KeywordRetrievePassagesAdapter(KNOWLEDGE_BASE, 3, 0.9);

            assertEquals(List.of(), strict.retrievePassages("鮭魚要煎幾分鐘？"));
        }

        @Test
        @DisplayName("完全沒交集時回空清單，不是丟例外：沒找到是正常結果")
        void returnsEmptyWhenNothingMatches() {
            // 挑「巴黎鐵塔」而不是「量子力學」：後者的「量」會撞上AVOCADO那段的「含量高」，
            // 拿來當「完全沒交集」的例子並不成立
            assertEquals(List.of(), adapter(3).retrievePassages("巴黎鐵塔"));
        }

        @Test
        @DisplayName("整串都是標點時不會當成全部命中，也不會除以零")
        void ignoresPunctuationOnlyQuestion() {
            assertEquals(List.of(), adapter(3).retrievePassages("？？？"));
        }
    }

    @Nested
    @DisplayName("建構時就擋下來")
    class Construction {

        @Test
        @DisplayName("知識庫是 null 時拒絕")
        void rejectNullKnowledgeBase() {
            assertThrows(NullPointerException.class,
                    () -> new KeywordRetrievePassagesAdapter(null));
        }

        @Test
        @DisplayName("topK 不是正數時拒絕：回零筆片段沒有意義")
        void rejectNonPositiveTopK() {
            assertThrows(IllegalArgumentException.class,
                    () -> new KeywordRetrievePassagesAdapter(KNOWLEDGE_BASE, 0, 0.5));
        }
    }
}
