package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RemoveEventService")
class RemoveEventServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final EventId ID = EventId.newId();

    @Test
    @DisplayName("刪到了：把擁有者和 id 交給 port，什麼都不丟")
    void removes() {
        List<OwnerId> owners = new ArrayList<>();
        List<EventId> deleted = new ArrayList<>();
        DeleteEventPort port = (owner, id) -> owners.add(owner) && deleted.add(id);

        new RemoveEventService(port).removeEvent(OWNER, ID);

        assertEquals(List.of(OWNER), owners);
        assertEquals(List.of(ID), deleted);
    }

    @Test
    @DisplayName("沒刪到東西（不存在、已經刪過、或是別人的）：EventNotFoundException")
    void notFound() {
        DeleteEventPort nothingThere = (owner, id) -> false;

        assertThrows(EventNotFoundException.class, () -> new RemoveEventService(nothingThere).removeEvent(OWNER, ID));
    }

    @Test
    @DisplayName("擁有者或 id 是 null：IllegalArgumentException，不碰資料庫")
    void rejectsNull() {
        DeleteEventPort mustNotDelete = (owner, id) -> fail("不該碰資料庫");
        RemoveEventService service = new RemoveEventService(mustNotDelete);

        assertThrows(IllegalArgumentException.class, () -> service.removeEvent(OWNER, null));
        assertThrows(IllegalArgumentException.class, () -> service.removeEvent(null, ID));
    }

    @Test
    @DisplayName("資料庫出事時，port 的例外原樣往外丟")
    void propagatesPortFailure() {
        IllegalStateException failure = new IllegalStateException("資料庫連不上");
        DeleteEventPort broken = (owner, id) -> {
            throw failure;
        };

        assertSame(failure, assertThrows(IllegalStateException.class, () -> new RemoveEventService(broken).removeEvent(OWNER, ID)));
    }
}
