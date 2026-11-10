package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.FindEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventPort;

import java.util.Objects;
import java.util.Optional;

/** 實作「用 id 找一個行程」。 */
public final class FindEventService implements FindEventUseCase {

    private final LoadEventPort loadEventPort;

    public FindEventService(LoadEventPort loadEventPort) {
        this.loadEventPort = Objects.requireNonNull(loadEventPort, "loadEventPort 不可為 null");
    }

    @Override
    public Optional<ScheduledEvent> findEvent(OwnerId owner, EventId id) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        if (id == null) {
            throw new IllegalArgumentException("行程 ID 不可為 null");
        }
        return loadEventPort.loadOne(owner, id);
    }
}
