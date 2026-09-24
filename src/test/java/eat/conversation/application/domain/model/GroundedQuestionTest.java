package eat.conversation.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

@DisplayName("GroundedQuestion")
class GroundedQuestionTest {

    private static final Passage COOKING =
            new Passage("中火煎四分鐘再翻面，避免過熟。", "鮭魚.md > 烹調建議");
    private static final Passage STORAGE =
            new Passage("新鮮的需冷藏於 0~4°C。", "鮭魚.md > 保存方式");

    @Nested
    @DisplayName("有檢索到片段")
    class WithPassages {

        @Test
        @DisplayName("組成「參考資料 → 防幻覺指令 → 問題」三段")
        void composesText() {
            GroundedQuestion grounded =
                    new GroundedQuestion("鮭魚要煎幾分鐘？", List.of(COOKING));

            assertEquals("""
                    參考資料：
                    [1]（鮭魚.md > 烹調建議）中火煎四分鐘再翻面，避免過熟。

                    請依據上述參考資料回答，資料中沒有提到的內容不要自行推測。

                    問題：鮭魚要煎幾分鐘？""", grounded.text());
        }

        @Test
        @DisplayName("多個片段依序編號，模型才有辦法指名出處")
        void numbersPassagesInOrder() {
            GroundedQuestion grounded =
                    new GroundedQuestion("鮭魚怎麼處理？", List.of(COOKING, STORAGE));

            assertEquals("""
                    參考資料：
                    [1]（鮭魚.md > 烹調建議）中火煎四分鐘再翻面，避免過熟。
                    [2]（鮭魚.md > 保存方式）新鮮的需冷藏於 0~4°C。

                    請依據上述參考資料回答，資料中沒有提到的內容不要自行推測。

                    問題：鮭魚怎麼處理？""", grounded.text());
        }
    }

    @Nested
    @DisplayName("沒有檢索到片段")
    class WithoutPassages {

        @Test
        @DisplayName("就是一般提問，不加參考資料也不加指令")
        void fallsBackToPlainQuestion() {
            GroundedQuestion grounded =
                    new GroundedQuestion("鮭魚要煎幾分鐘？", List.of());

            assertEquals("鮭魚要煎幾分鐘？", grounded.text());
        }
    }

    @Nested
    @DisplayName("不合規的輸入")
    class InvalidInput {

        @ParameterizedTest(name = "提問 = [{0}]")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t", "\n"})
        @DisplayName("提問是 null 或空白時拒絕（規則 4）")
        void rejectBlankQuestion(String question) {
            assertThrows(IllegalArgumentException.class,
                    () -> new GroundedQuestion(question, List.of(COOKING)));
        }

        @Test
        @DisplayName("片段清單是 null 時拒絕：沒檢索到東西該傳空清單，不是 null")
        void rejectNullPassages() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GroundedQuestion("鮭魚要煎幾分鐘？", null));
        }
    }

    @Test
    @DisplayName("建構後外部再改原本的清單，影響不到已經建好的提問")
    void copiesPassages() {
        List<Passage> mutable = new ArrayList<>(List.of(COOKING));
        GroundedQuestion grounded = new GroundedQuestion("鮭魚要煎幾分鐘？", mutable);

        mutable.add(STORAGE);

        assertEquals(List.of(COOKING), grounded.passages());
    }
}
