package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.GuestDemo;
import io.github.andersonchen.talkcal.calendar.application.port.in.GuestDemoLimitException;
import io.github.andersonchen.talkcal.calendar.application.port.in.ShowGuestDemoUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.SignInRequiredException;
import io.github.andersonchen.talkcal.calendar.application.port.out.GuestDemoUsagePort;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.OptionalInt;

/** clock 的時區決定哪天換日，也決定回答裡的「明天」是哪天 */
public final class ShowGuestDemoService implements ShowGuestDemoUseCase {

    private final GuestDemoUsagePort usage;
    private final Clock clock;

    public ShowGuestDemoService(GuestDemoUsagePort usage, Clock clock) {
        this.usage = Objects.requireNonNull(usage, "usage 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    @Override
    public Shown show(String client, String question) {
        if (client == null || client.isBlank()) {
            throw new IllegalArgumentException("分不出是哪個訪客");
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        // 先找答案再計數：送錯的請求不該用掉訪客的次數
        GuestDemo.Answer answer = GuestDemo.answer(question, today).orElseThrow(SignInRequiredException::new);
        OptionalInt used = usage.use(client, today, DAILY_LIMIT);
        if (used.isEmpty()) {
            throw new GuestDemoLimitException(Duration.between(now, today.plusDays(1).atStartOfDay(now.getZone())));
        }
        return new Shown(answer, DAILY_LIMIT - used.getAsInt());
    }
}
