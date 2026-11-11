package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DayPeriod;
import io.github.andersonchen.talkcal.calendar.application.domain.model.FreeSlot;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.SessionPlanner;
import io.github.andersonchen.talkcal.calendar.application.port.in.FindFreeSlotsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.PlanSessionsUseCase;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 實作「在空檔裡排時段」：找出夠長的空檔（FindFreeSlotsUseCase），交給 SessionPlanner 每天挑一段。
 * 空檔的最短長度就是每段的長度，太短的在找空檔時就濾掉了。
 */
public final class PlanSessionsService implements PlanSessionsUseCase {

    private final FindFreeSlotsUseCase findFreeSlots;

    public PlanSessionsService(FindFreeSlotsUseCase findFreeSlots) {
        this.findFreeSlots = Objects.requireNonNull(findFreeSlots, "findFreeSlots 不可為 null");
    }

    @Override
    public List<FreeSlot> planSessions(OwnerId owner, LocalDate start, LocalDate endExclusive, DayPeriod period, Duration length) {
        // 期間本身合不合規（null、結束不晚於開始、超過 100 天）交給找空檔時的 DateRange，這裡只管排時段自己的上限
        long days = start == null || endExclusive == null ? 0 : ChronoUnit.DAYS.between(start, endExclusive);
        if (days > MAX_DAYS) {
            throw new IllegalArgumentException("一次最多排 " + MAX_DAYS + " 天，這次要了 " + days + " 天");
        }
        return SessionPlanner.plan(findFreeSlots.findFreeSlots(owner, start, endExclusive, period, length), length);
    }
}
