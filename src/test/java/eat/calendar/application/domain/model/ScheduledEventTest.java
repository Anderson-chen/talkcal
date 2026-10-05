package eat.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ScheduledEvent")
class ScheduledEventTest {

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0));

    @Test
    @DisplayName("排進行事曆時發一個新身分，行程本身原樣保留")
    void scheduleAssignsNewId() {
        ScheduledEvent scheduled = ScheduledEvent.schedule(DINNER);

        assertNotNull(scheduled.id());
        assertSame(DINNER, scheduled.event());
    }

    @Test
    @DisplayName("同一個行程排兩次是兩筆不同的行程")
    void schedulingTwiceGivesTwoIdentities() {
        assertNotEquals(ScheduledEvent.schedule(DINNER).id(), ScheduledEvent.schedule(DINNER).id());
    }

    @Test
    @DisplayName("沒有 ID 或沒有行程時拒絕")
    void rejectsMissingParts() {
        assertThrows(IllegalArgumentException.class, () -> new ScheduledEvent(null, DINNER));
        assertThrows(IllegalArgumentException.class, () -> new ScheduledEvent(EventId.newId(), null));
    }
}
