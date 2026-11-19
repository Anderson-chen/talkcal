package io.github.andersonchen.talkcal.app.job;

import io.github.andersonchen.talkcal.auth.application.port.in.ExpireGuestsUseCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 每天刪掉太久沒用的訪客帳號，連同帳號名下的行程和對話：訪客 cookie 已經過期，沒有人能再登入那個帳號，留著只是占空間。
 * 期限跟訪客 cookie 一樣（talkcal.guest.lifetime）。
 */
public final class GuestAccountCleanup {

    private static final Logger log = LoggerFactory.getLogger(GuestAccountCleanup.class);

    private final ExpireGuestsUseCase expireGuests;
    private final Duration lifetime;
    private final Clock clock;

    public GuestAccountCleanup(ExpireGuestsUseCase expireGuests, Duration lifetime, Clock clock) {
        this.expireGuests = Objects.requireNonNull(expireGuests, "expireGuests 不可為 null");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    // 跟其他清理錯開。多台同時執行也沒關係：刪過的帳號查不到，重複執行只是刪 0 個
    @Scheduled(cron = "0 45 4 * * *", zone = "${calendar.zone}")
    void run() {
        int deleted = expireGuests.expireInactiveSince(clock.instant().minus(lifetime));
        log.info("刪掉太久沒用的訪客帳號：{} 個（超過 {} 天沒用）", deleted, lifetime.toDays());
    }
}
