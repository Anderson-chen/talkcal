package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AddEventsService")
class AddEventsServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0));
    private static final CalendarEvent DENTIST =
            CalendarEvent.startingAt("看牙醫", LocalDateTime.of(2026, 10, 14, 9, 0));

    private static final SaveEventsPort MUST_NOT_SAVE = (owner, events) -> fail("不該存檔");

    // 假資料庫：記下每次收到的那一批
    private static final class RecordingPort implements SaveEventsPort {
        int calls;
        OwnerId owner;
        List<ScheduledEvent> saved;

        @Override
        public void save(OwnerId owner, List<ScheduledEvent> events) {
            calls++;
            this.owner = owner;
            saved = events;
        }
    }

    @Test
    @DisplayName("多筆行程一次存起來，每筆都發了 ID，順序不變，存在 owner 名下")
    void schedulesAndSavesAllInOneCall() {
        RecordingPort port = new RecordingPort();

        List<ScheduledEvent> result = new AddEventsService(port).addEvents(OWNER, List.of(DINNER, DENTIST));

        assertEquals(1, port.calls);
        assertEquals(OWNER, port.owner);
        assertEquals(result, port.saved);
        assertEquals(List.of(DINNER, DENTIST), result.stream().map(ScheduledEvent::event).toList());
    }

    @Test
    @DisplayName("空清單：什麼都不做，不驚動資料庫")
    void emptyListDoesNothing() {
        assertTrue(new AddEventsService(MUST_NOT_SAVE).addEvents(OWNER, List.of()).isEmpty());
    }

    @Test
    @DisplayName("擁有者是 null、清單是 null 或含 null：IllegalArgumentException，不存檔")
    void rejectsNulls() {
        AddEventsService service = new AddEventsService(MUST_NOT_SAVE);

        assertThrows(IllegalArgumentException.class, () -> service.addEvents(null, List.of(DINNER)));
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, null));
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, Arrays.asList(DINNER, null)));
    }

    @Test
    @DisplayName("不可變清單也能檢查 null（List.of 的 contains(null) 會丟 NullPointerException）")
    void worksWithImmutableLists() {
        RecordingPort port = new RecordingPort();

        new AddEventsService(port).addEvents(OWNER, List.of(DINNER));

        assertEquals(1, port.saved.size());
    }

    @Test
    @DisplayName("資料庫出事時，port 的例外原樣往外丟")
    void propagatesPortFailure() {
        IllegalStateException failure = new IllegalStateException("資料庫連不上");
        SaveEventsPort broken = (owner, events) -> {
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new AddEventsService(broken).addEvents(OWNER, List.of(DINNER))));
    }
}
