package io.github.andersonchen.talkcal.app.job;

import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * 每天刪掉超過保留期限的冪等鍵：重送是幾秒到幾分鐘內的事，留久了只讓表一直長大。
 * 清掉之後同一個鍵再送會被當成新的一次，代價見 V13。
 *
 * 放在 app.job：直接用 adapter 做這張表自己的維護，ArchitectureTest 只准 app.job 這樣做。
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
            // 0 會把剛送出、還可能重送的鍵也刪掉，等於沒有冪等
            throw new IllegalArgumentException("保留期限要大於 0：" + retention);
        }
    }

    // 跟對話的歸檔（四點）錯開。多台同時執行也沒關係，重複執行只是刪 0 個
    @Scheduled(cron = "0 30 4 * * *", zone = "${calendar.zone}")
    void run() {
        int deleted = events.deleteIdempotencyKeysCreatedBefore(clock.instant().minus(retention));
        log.info("清掉新增行程的舊冪等鍵：{} 個（超過 {} 小時）", deleted, retention.toHours());
    }
}
