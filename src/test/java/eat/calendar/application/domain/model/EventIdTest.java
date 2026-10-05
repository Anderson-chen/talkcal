package eat.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("EventId")
class EventIdTest {

    @Test
    @DisplayName("每次發的 ID 都不一樣")
    void newIdsAreUnique() {
        assertNotEquals(EventId.newId(), EventId.newId());
    }

    @Test
    @DisplayName("字串來回轉換不變")
    void roundTripsThroughText() {
        UUID uuid = UUID.randomUUID();

        assertEquals(new EventId(uuid), EventId.of(uuid.toString()));
        assertEquals(uuid.toString(), new EventId(uuid).toString());
    }

    @Test
    @DisplayName("值是 null 時拒絕")
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new EventId(null));
    }

    @ParameterizedTest(name = "字串 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-uuid"})
    @DisplayName("字串空白或格式不對時拒絕")
    void rejectsBadText(String text) {
        assertThrows(IllegalArgumentException.class, () -> EventId.of(text));
    }
}
