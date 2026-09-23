package eat.conversation.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Passage")
class PassageTest {

    @Test
    @DisplayName("保存片段文字與來源")
    void keepsTextAndSource() {
        Passage passage = new Passage("中火煎四分鐘再翻面。", "鮭魚.md > 烹調建議");

        assertEquals("中火煎四分鐘再翻面。", passage.text());
        assertEquals("鮭魚.md > 烹調建議", passage.source());
    }

    @ParameterizedTest(name = "文字 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("文字是 null 或空白時拒絕（規則 4）")
    void rejectBlankText(String text) {
        assertThrows(IllegalArgumentException.class, () -> new Passage(text, "鮭魚.md"));
    }

    @ParameterizedTest(name = "來源 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("來源是 null 或空白時拒絕：沒有出處的知識無法查證")
    void rejectBlankSource(String source) {
        assertThrows(IllegalArgumentException.class, () -> new Passage("中火煎四分鐘再翻面。", source));
    }
}
