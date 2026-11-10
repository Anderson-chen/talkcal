package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventPort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FindEventService")
class FindEventServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final ScheduledEvent LUNCH =
            ScheduledEvent.schedule(CalendarEvent.startingAt("吃飯", LocalDateTime.of(2026, 10, 7, 11, 0)));

    @Test
    @DisplayName("把擁有者和 id 交給 port，找到什麼就回什麼")
    void finds() {
        List<OwnerId> owners = new ArrayList<>();
        LoadEventPort port = (owner, id) -> {
            owners.add(owner);
            return id.equals(LUNCH.id()) ? Optional.of(LUNCH) : Optional.empty();
        };
        FindEventService service = new FindEventService(port);

        assertEquals(Optional.of(LUNCH), service.findEvent(OWNER, LUNCH.id()));
        assertEquals(Optional.empty(), service.findEvent(OWNER, EventId.newId()));
        assertEquals(List.of(OWNER, OWNER), owners);
    }

    @Test
    @DisplayName("擁有者或 id 是 null：IllegalArgumentException，不碰資料庫")
    void rejectsNull() {
        FindEventService service = new FindEventService((owner, id) -> fail("不該碰資料庫"));

        assertThrows(IllegalArgumentException.class, () -> service.findEvent(null, LUNCH.id()));
        assertThrows(IllegalArgumentException.class, () -> service.findEvent(OWNER, null));
    }
}
