package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.port.in.CountActiveUsersUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.CountActiveOwnersPort;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 實作「最近一段時間內有幾個人在用」：把「最近 window」換成一個時間點，交給 port 去數。
 * 時鐘從外面給（同 ParseEventsService），測試才能固定「現在」。
 */
public final class CountActiveUsersService implements CountActiveUsersUseCase {

    private final CountActiveOwnersPort activeOwners;
    private final Clock clock;

    public CountActiveUsersService(CountActiveOwnersPort activeOwners, Clock clock) {
        this.activeOwners = Objects.requireNonNull(activeOwners, "activeOwners 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    @Override
    public int countActiveUsers(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("期間要是正的：" + window);
        }
        return activeOwners.countActiveOwners(clock.instant().minus(window));
    }
}
