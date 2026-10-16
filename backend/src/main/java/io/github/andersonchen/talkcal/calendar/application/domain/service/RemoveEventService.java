package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;

import java.util.Objects;

/**
 * 實作「從行事曆拿掉一個行程」。
 *
 * 只刪一次 SQL，不先查再刪：先查再刪是兩次往返，而且兩次之間別人可能已經刪了，查到也不保證刪得到。
 * 直接刪、看有沒有刪到，一次就知道結果。
 *
 * 擁有者也是同一句 SQL 的條件，不是先查出這筆是誰的再比對：別人的行程跟不存在的行程走同一條路、回同一個例外，
 * 從回應分不出「這個 id 存在但不是你的」（#104）。
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
