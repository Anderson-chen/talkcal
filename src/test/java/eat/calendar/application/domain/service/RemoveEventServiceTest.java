package eat.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import eat.calendar.application.domain.model.EventId;
import eat.calendar.application.port.in.EventNotFoundException;
import eat.calendar.application.port.out.DeleteEventPort;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RemoveEventService")
class RemoveEventServiceTest {

    private static final EventId ID = EventId.newId();

    @Test
    @DisplayName("刪到了：把 id 交給 port，什麼都不丟")
    void removes() {
        List<EventId> deleted = new ArrayList<>();
        DeleteEventPort port = id -> deleted.add(id);

        new RemoveEventService(port).removeEvent(ID);

        assertEquals(List.of(ID), deleted);
    }

    @Test
    @DisplayName("沒刪到東西（不存在、或已經刪過）：EventNotFoundException")
    void notFound() {
        DeleteEventPort nothingThere = id -> false;

        assertThrows(EventNotFoundException.class, () -> new RemoveEventService(nothingThere).removeEvent(ID));
    }

    @Test
    @DisplayName("id 是 null：IllegalArgumentException，不碰資料庫")
    void rejectsNull() {
        DeleteEventPort mustNotDelete = id -> fail("不該碰資料庫");

        assertThrows(IllegalArgumentException.class, () -> new RemoveEventService(mustNotDelete).removeEvent(null));
    }

    @Test
    @DisplayName("資料庫出事時，port 的例外原樣往外丟")
    void propagatesPortFailure() {
        IllegalStateException failure = new IllegalStateException("資料庫連不上");
        DeleteEventPort broken = id -> {
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> new RemoveEventService(broken).removeEvent(ID)));
    }
}
