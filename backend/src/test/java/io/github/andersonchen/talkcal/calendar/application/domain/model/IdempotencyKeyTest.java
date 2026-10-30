package io.github.andersonchen.talkcal.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("IdempotencyKey")
class IdempotencyKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10", "assistant:42", "a", "k6.run_1"})
    @DisplayName("UUID、「前綴:編號」這類常見寫法都收")
    void acceptsCommonShapes(String text) {
        assertEquals(text, new IdempotencyKey(text).value());
    }

    @Test
    @DisplayName("剛好 100 個字收、101 個字不收")
    void lengthLimit() {
        assertEquals(100, new IdempotencyKey("a".repeat(100)).value().length());
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey("a".repeat(101)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "有中文", "has space", "line\nbreak", "semi;colon"})
    @DisplayName("反例：空的、空白、非英數（會進 log 和資料庫的怪字元）都不收")
    void rejectsOddCharacters(String text) {
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey(text));
    }
}
