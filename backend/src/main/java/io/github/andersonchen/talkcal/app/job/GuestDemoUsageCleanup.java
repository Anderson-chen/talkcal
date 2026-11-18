package io.github.andersonchen.talkcal.app.job;

import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcGuestDemoUsageAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 每天刪掉今天以前的訪客示範次數：換日後就不再用到，留著只是多存訪客的 IP。
 *
 * 放在 app.job 的理由見 IdempotencyKeyCleanup。
 */
public final class GuestDemoUsageCleanup {

    private static final Logger log = LoggerFactory.getLogger(GuestDemoUsageCleanup.class);

    private final JdbcGuestDemoUsageAdapter usage;
    // 時區要跟計數時的換日一樣（calendar.zone），不然會刪到還在用的那一天
    private final Clock clock;

    public GuestDemoUsageCleanup(JdbcGuestDemoUsageAdapter usage, Clock clock) {
        this.usage = Objects.requireNonNull(usage, "usage 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    // 跟其他清理錯開。多台同時執行也沒關係，重複執行只是刪 0 列
    @Scheduled(cron = "0 45 4 * * *", zone = "${calendar.zone}")
    void run() {
        int deleted = usage.deleteBefore(LocalDate.now(clock));
        log.info("清掉前幾天的訪客示範次數：{} 列", deleted);
    }
}
