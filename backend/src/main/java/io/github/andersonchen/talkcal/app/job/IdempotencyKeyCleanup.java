package io.github.andersonchen.talkcal.app.job;

import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 每天清一次新增行程用過的冪等鍵（#51）：記下超過保留期限的，刪掉。
 *
 * 為什麼可以清：鍵只是用來認出「同一次新增的重送」，重送是幾秒到幾分鐘內的事（使用者看到失敗再按一次）。
 * 留太久沒有好處，只會讓表一直長大。清掉之後同一個鍵再送會被當成新的一次 —— 代價寫在 V12。
 *
 * 只刪鍵、不碰行程：行程在 calendar_event，鍵在自己的表（calendar_add_request）。
 * 放在 app.job 的理由同 AssistantMemoryCleanup：直接用 adapter 做這張表自己的維護，ArchitectureTest 只准 app.job 這樣做。
 */
public final class IdempotencyKeyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyCleanup.class);

    private final JdbcCalendarEventAdapter events;
    private final Duration retention;
    private final Clock clock;

    public IdempotencyKeyCleanup(JdbcCalendarEventAdapter events, Duration retention, Clock clock) {
        this.events = Objects.requireNonNull(events, "events 不可為 null");
        this.retention = Objects.requireNonNull(retention, "retention 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
        if (retention.isNegative() || retention.isZero()) {
            // 0 會把剛送出、正等著重送的鍵也刪掉，等於沒有冪等
            throw new IllegalArgumentException("保留期限要大於 0：" + retention);
        }
    }

    // 每天凌晨四點半（台北時間）：跟清對話的錯開半小時。多台一起跑也沒關係，DELETE 重跑一次只是刪 0 個
    @Scheduled(cron = "0 30 4 * * *", zone = "${calendar.zone}")
    void run() {
        int deleted = events.deleteIdempotencyKeysCreatedBefore(clock.instant().minus(retention));
        log.info("清掉新增行程的舊冪等鍵：{} 個（超過 {} 小時）", deleted, retention.toHours());
    }
}
