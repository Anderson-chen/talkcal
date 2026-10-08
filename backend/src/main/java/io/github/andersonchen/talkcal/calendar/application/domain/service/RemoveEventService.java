package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;

import java.util.Objects;

/**
 * 實作「從行事曆拿掉一個行程」。
 *
 * 只刪一次 SQL，不先查再刪：先查再刪是兩次往返，而且兩次之間別人可能已經刪了，查到也不保證刪得到。
 * 直接刪、看有沒有刪到，一次就知道結果。
 */
public final class RemoveEventService implements RemoveEventUseCase {

    private final DeleteEventPort deleteEventPort;

    public RemoveEventService(DeleteEventPort deleteEventPort) {
        this.deleteEventPort = Objects.requireNonNull(deleteEventPort, "deleteEventPort 不可為 null");
    }

    @Override
    public void removeEvent(EventId id) {
        if (id == null) {
            throw new IllegalArgumentException("行程 ID 不可為 null");
        }
        if (!deleteEventPort.delete(id)) {
            throw new EventNotFoundException(id);
        }
    }
}
