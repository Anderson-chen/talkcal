package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventChangedException;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.UpdateEventPort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UpdateEventService")
class UpdateEventServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final EventId ID = EventId.newId();
    private static final CalendarEvent BEFORE = CalendarEvent.startingAt("吃飯", LocalDateTime.of(2026, 10, 7, 11, 0));
    private static final CalendarEvent AFTER = CalendarEvent.startingAt("喝咖啡", LocalDateTime.of(2026, 10, 7, 15, 0));

    private static final LoadEventPort MUST_NOT_LOAD = (owner, id) -> fail("改到了就不必再查");

    @Test
    @DisplayName("改到了：把擁有者、改之前的樣子、改之後的樣子交給 port，回同一個 id、新的內容")
    void updates() {
        List<Object[]> calls = new ArrayList<>();
        UpdateEventPort port = (owner, expected, replacement) -> calls.add(new Object[] {owner, expected, replacement});

        ScheduledEvent updated = new UpdateEventService(port, MUST_NOT_LOAD).updateEvent(OWNER, ID, BEFORE, AFTER);

        assertEquals(new ScheduledEvent(ID, AFTER), updated);
        assertEquals(1, calls.size());
        assertEquals(OWNER, calls.getFirst()[0]);
        assertEquals(new ScheduledEvent(ID, BEFORE), calls.getFirst()[1]);
        assertEquals(AFTER, calls.getFirst()[2]);
    }

    @Test
    @DisplayName("沒改到、那筆也不在（不存在、刪掉了、別人的）：EventNotFoundException")
    void notFound() {
        UpdateEventPort nothingChanged = (owner, expected, replacement) -> false;
        LoadEventPort nothingThere = (owner, id) -> Optional.empty();

        assertThrows(EventNotFoundException.class,
                () -> new UpdateEventService(nothingChanged, nothingThere).updateEvent(OWNER, ID, BEFORE, AFTER));
    }

    @Test
    @DisplayName("反例：沒改到、但那筆還在 —— 就是在呼叫端看到之後被改過了：EventChangedException，不是找不到")
    void changedMeanwhile() {
        UpdateEventPort nothingChanged = (owner, expected, replacement) -> false;
        LoadEventPort stillThere = (owner, id) -> Optional.of(new ScheduledEvent(ID, AFTER));

        assertThrows(EventChangedException.class,
                () -> new UpdateEventService(nothingChanged, stillThere).updateEvent(OWNER, ID, BEFORE, AFTER));
    }

    @Test
    @DisplayName("參數是 null：IllegalArgumentException，不碰資料庫")
    void rejectsNull() {
        UpdateEventPort mustNotUpdate = (owner, expected, replacement) -> fail("不該碰資料庫");
        UpdateEventService service = new UpdateEventService(mustNotUpdate, MUST_NOT_LOAD);

        assertThrows(IllegalArgumentException.class, () -> service.updateEvent(null, ID, BEFORE, AFTER));
        assertThrows(IllegalArgumentException.class, () -> service.updateEvent(OWNER, null, BEFORE, AFTER));
        assertThrows(IllegalArgumentException.class, () -> service.updateEvent(OWNER, ID, null, AFTER));
        assertThrows(IllegalArgumentException.class, () -> service.updateEvent(OWNER, ID, BEFORE, null));
    }

    @Test
    @DisplayName("資料庫出事時，port 的例外原樣往外丟")
    void propagatesPortFailure() {
        IllegalStateException failure = new IllegalStateException("資料庫連不上");
        UpdateEventPort broken = (owner, expected, replacement) -> {
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new UpdateEventService(broken, MUST_NOT_LOAD).updateEvent(OWNER, ID, BEFORE, AFTER)));
    }
}
