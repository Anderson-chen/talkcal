package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.Optional;

/**
 * 只找得到 owner 自己的：別人的跟不存在的都是 Optional.empty()。
 *
 * 失敗時：參數是 null → IllegalArgumentException；資料庫出事 → IllegalStateException。
 */
public interface FindEventUseCase {

    Optional<ScheduledEvent> findEvent(OwnerId owner, EventId id);
}
