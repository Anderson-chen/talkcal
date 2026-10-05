package eat.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("EventDescription")
class EventDescriptionTest {

    @Test
    @DisplayName("保存描述文字")
    void keepsText() {
        assertEquals("明天下午三點跟小明吃飯", new EventDescription("明天下午三點跟小明吃飯").text());
    }

    @ParameterizedTest(name = "描述 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("描述是 null 或空白時拒絕")
    void rejectsBlankText(String text) {
        assertThrows(IllegalArgumentException.class, () -> new EventDescription(text));
    }
}
