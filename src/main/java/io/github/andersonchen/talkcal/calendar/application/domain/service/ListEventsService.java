package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 實作「列出一段期間裡的行程」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則 —— 期間合不合規交給 DateRange。
 */
public final class ListEventsService implements ListEventsUseCase {

    private final LoadEventsPort loadEventsPort;

    public ListEventsService(LoadEventsPort loadEventsPort) {
        this.loadEventsPort = Objects.requireNonNull(loadEventsPort, "loadEventsPort 不可為 null");
    }

    @Override
    public List<ScheduledEvent> listEvents(LocalDate start, LocalDate endExclusive) {
        // 先建 DateRange：不合規的期間（倒過來、太長）連資料庫都不會碰到
        DateRange range = new DateRange(start, endExclusive);
        return List.copyOf(loadEventsPort.load(range));
    }
}
