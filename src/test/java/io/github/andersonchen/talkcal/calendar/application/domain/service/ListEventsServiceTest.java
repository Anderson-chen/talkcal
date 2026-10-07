package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ListEventsService")
class ListEventsServiceTest {

    private static final LocalDate SEP_28 = LocalDate.of(2026, 9, 28);
    private static final LocalDate NOV_09 = LocalDate.of(2026, 11, 9);

    private static final ScheduledEvent DINNER =
            ScheduledEvent.schedule(CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0)));

    @Test
    @DisplayName("把期間交給 port，原樣回傳")
    void passesRangeAndReturnsEvents() {
        List<DateRange> asked = new ArrayList<>();
        LoadEventsPort port = range -> {
            asked.add(range);
            return List.of(DINNER);
        };

        List<ScheduledEvent> events = new ListEventsService(port).listEvents(SEP_28, NOV_09);

        assertEquals(List.of(DINNER), events);
        assertEquals(List.of(new DateRange(SEP_28, NOV_09)), asked);
    }

    @Test
    @DisplayName("期間不合規時拒絕，而且不會碰資料庫")
    void rejectsBadRangeBeforeLoading() {
        LoadEventsPort mustNotLoad = range -> fail("不合規的期間不該碰資料庫");
        ListEventsService service = new ListEventsService(mustNotLoad);

        assertThrows(IllegalArgumentException.class, () -> service.listEvents(NOV_09, SEP_28));
        assertThrows(IllegalArgumentException.class, () -> service.listEvents(SEP_28, SEP_28.plusYears(1)));
    }

    @Test
    @DisplayName("回傳的清單改不動")
    void returnsUnmodifiableList() {
        List<ScheduledEvent> events = new ListEventsService(range -> new ArrayList<>(List.of(DINNER))).listEvents(SEP_28, NOV_09);

        assertThrows(UnsupportedOperationException.class, events::clear);
    }
}
