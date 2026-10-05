package eat.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

@DisplayName("CalendarEvent")
class CalendarEventTest {

    private static final LocalDateTime THREE_PM = LocalDateTime.of(2026, 10, 6, 15, 0);

    @Test
    @DisplayName("保存標題與起訖時間")
    void keepsFields() {
        CalendarEvent event = new CalendarEvent("跟小明吃飯", THREE_PM, THREE_PM.plusHours(2));

        assertEquals("跟小明吃飯", event.title());
        assertEquals(THREE_PM, event.start());
        assertEquals(THREE_PM.plusHours(2), event.end());
    }

    @ParameterizedTest(name = "標題 = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("標題是 null 或空白時拒絕")
    void rejectsBlankTitle(String title) {
        assertThrows(IllegalArgumentException.class, () -> new CalendarEvent(title, THREE_PM, THREE_PM.plusHours(1)));
    }

    @Test
    @DisplayName("開始或結束時間是 null 時拒絕")
    void rejectsMissingTimes() {
        assertThrows(IllegalArgumentException.class, () -> new CalendarEvent("開會", null, THREE_PM));
        assertThrows(IllegalArgumentException.class, () -> new CalendarEvent("開會", THREE_PM, null));
    }

    @Test
    @DisplayName("結束早於開始時拒絕：「晚上十點到凌晨一點」被算成同一天")
    void rejectsEndBeforeStart() {
        LocalDateTime tenPm = LocalDateTime.of(2026, 10, 6, 22, 0);
        LocalDateTime oneAmSameDay = LocalDateTime.of(2026, 10, 6, 1, 0);

        assertThrows(IllegalArgumentException.class, () -> new CalendarEvent("唱歌", tenPm, oneAmSameDay));
    }

    @Test
    @DisplayName("跨夜的行程沒問題")
    void acceptsOvernight() {
        LocalDateTime tenPm = LocalDateTime.of(2026, 10, 6, 22, 0);
        LocalDateTime oneAmNextDay = LocalDateTime.of(2026, 10, 7, 1, 0);

        assertEquals(oneAmNextDay, new CalendarEvent("唱歌", tenPm, oneAmNextDay).end());
    }

    @Test
    @DisplayName("結束等於開始時拒絕：零長度的行程沒有意義")
    void rejectsZeroLength() {
        assertThrows(IllegalArgumentException.class, () -> new CalendarEvent("開會", THREE_PM, THREE_PM));
    }

    @Test
    @DisplayName("只給開始時間：結束時間補上預設的一小時")
    void startingAtUsesDefaultDuration() {
        CalendarEvent event = CalendarEvent.startingAt("跟小明吃飯", THREE_PM);

        assertEquals(THREE_PM.plusHours(1), event.end());
    }

    @Test
    @DisplayName("只給開始時間但它是 null：丟規則的例外，不是 NullPointerException")
    void startingAtRejectsNullStart() {
        assertThrows(IllegalArgumentException.class, () -> CalendarEvent.startingAt("開會", null));
    }
}
