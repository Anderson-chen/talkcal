package eat.conversation.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Reply")
class ReplyTest {

    @Test
    @DisplayName("保存回覆文字")
    void keepsText() {
        assertEquals("牛肉麵", new Reply("牛肉麵").text());
    }

    @ParameterizedTest(name = "回覆 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("文字是 null 或空白時拒絕（規則 4）")
    void rejectBlankText(String text) {
        assertThrows(IllegalArgumentException.class, () -> new Reply(text));
    }
}
