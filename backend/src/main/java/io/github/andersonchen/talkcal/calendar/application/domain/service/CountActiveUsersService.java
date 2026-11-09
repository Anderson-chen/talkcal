package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.port.in.CountActiveUsersUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.CountActiveOwnersPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 實作「最近幾段時間內各有幾個人在用」：把每個「最近 window」換成一個時間點，一次交給 port 去數。
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
    public List<Integer> countActiveUsers(List<Duration> windows) {
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("至少要一個期間");
        }
        for (Duration window : windows) {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("期間要是正的：" + window);
            }
        }
        Instant now = clock.instant();
        return activeOwners.countActiveOwners(windows.stream().map(now::minus).toList());
    }
}
