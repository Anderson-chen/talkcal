package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DayPeriod;
import io.github.andersonchen.talkcal.calendar.application.domain.model.FreeSlot;
import io.github.andersonchen.talkcal.calendar.application.domain.model.FreeSlots;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.FindFreeSlotsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 實作「找空檔」：讀出那幾天的行程，交給 FreeSlots 算。
 * 跟 ParseEventsService 一樣從 Clock 取「現在」：時區由組裝根決定，測試給固定時鐘。
 */
public final class FindFreeSlotsService implements FindFreeSlotsUseCase {

    private final LoadEventsPort loadEventsPort;
    private final Clock clock;

    public FindFreeSlotsService(LoadEventsPort loadEventsPort, Clock clock) {
        this.loadEventsPort = Objects.requireNonNull(loadEventsPort, "loadEventsPort 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    @Override
    public List<FreeSlot> findFreeSlots(LocalDate start, LocalDate endExclusive, DayPeriod period, Duration minimum) {
        // 先建 DateRange：不合規的期間連資料庫都不會碰到
        DateRange range = new DateRange(start, endExclusive);
        List<ScheduledEvent> busy = loadEventsPort.load(range);
        return FreeSlots.find(range, period, minimum, busy.stream().map(ScheduledEvent::event).toList(), LocalDateTime.now(clock));
    }
}
