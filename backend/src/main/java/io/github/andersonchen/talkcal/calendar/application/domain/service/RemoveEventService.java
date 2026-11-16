package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;

import java.util.Objects;

/**
 * 直接刪、看有沒有刪到，不先查再刪：多一次往返，兩次之間別人也可能已經刪了。
 *
 * 擁有者也是刪除的條件，不先查出這筆是誰的再比對：別人的行程跟不存在的行程回同一個例外，
 * 從回應分不出「這個 id 存在但不是你的」。
 */
public final class RemoveEventService implements RemoveEventUseCase {

    private final DeleteEventPort deleteEventPort;

    public RemoveEventService(DeleteEventPort deleteEventPort) {
        this.deleteEventPort = Objects.requireNonNull(deleteEventPort, "deleteEventPort 不可為 null");
    }

    @Override
    public void removeEvent(OwnerId owner, EventId id) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        if (id == null) {
            throw new IllegalArgumentException("行程 ID 不可為 null");
        }
        if (!deleteEventPort.delete(owner, id)) {
            throw new EventNotFoundException(id);
        }
    }
}
