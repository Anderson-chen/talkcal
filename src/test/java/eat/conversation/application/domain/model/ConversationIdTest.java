package eat.conversation.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ConversationId")
class ConversationIdTest {

    @Test
    @DisplayName("從字串讀回來，跟原本那個相等（HTTP 往返靠這個）")
    void roundTripsThroughText() {
        ConversationId id = ConversationId.newId();

        assertEquals(id, ConversationId.of(id.toString()));
    }

    @Test
    @DisplayName("每次發的都不一樣")
    void newIdsAreUnique() {
        assertNotEquals(ConversationId.newId(), ConversationId.newId());
    }

    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "abc", "1234"})
    @DisplayName("空白或格式不對：IllegalArgumentException（HTTP 那頭翻成 400）")
    void rejectsMalformedText(String text) {
        assertThrows(IllegalArgumentException.class, () -> ConversationId.of(text));
    }
}
