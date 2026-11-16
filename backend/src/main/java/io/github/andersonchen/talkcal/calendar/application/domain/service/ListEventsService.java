package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class ListEventsService implements ListEventsUseCase {

    private final LoadEventsPort loadEventsPort;

    public ListEventsService(LoadEventsPort loadEventsPort) {
        this.loadEventsPort = Objects.requireNonNull(loadEventsPort, "loadEventsPort 不可為 null");
    }

    @Override
    public List<ScheduledEvent> listEvents(OwnerId owner, LocalDate start, LocalDate endExclusive) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        // 先建 DateRange：不合規的期間（倒過來、太長）連資料庫都不會碰到
        DateRange range = new DateRange(start, endExclusive);
        return List.copyOf(loadEventsPort.load(owner, range));
    }
}
