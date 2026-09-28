package eat.conversation.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Question")
class QuestionTest {

    @Test
    @DisplayName("保存提問文字")
    void keepsText() {
        assertEquals("鮭魚要煎幾分鐘？", new Question("鮭魚要煎幾分鐘？").text());
    }

    @ParameterizedTest(name = "提問 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("文字是 null 或空白時拒絕（規則 4）")
    void rejectBlankText(String text) {
        assertThrows(IllegalArgumentException.class, () -> new Question(text));
    }
}
