package io.github.andersonchen.talkcal.calendar.adapter.in.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ActiveUsersMetrics")
class ActiveUsersMetricsTest {

    private static final Duration REFRESH = Duration.ofMinutes(1);
    private static final List<Duration> WINDOWS = List.of(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock();

    private double gauge(String window) {
        return registry.get("talkcal.active.users").tag("window", window).gauge().value();
    }

    @Test
    @DisplayName("三個期間各一條 gauge，一次問 use case 就拿到三個數")
    void oneQueryForAllWindows() {
        List<List<Duration>> asked = new ArrayList<>();
        new ActiveUsersMetrics(windows -> {
            asked.add(windows);
            return List.of(2, 7, 9);
        }, REFRESH, clock).bindTo(registry);

        assertEquals(2, gauge("1d"));
        assertEquals(7, gauge("7d"));
        assertEquals(9, gauge("30d"));
        assertEquals(List.of(WINDOWS), asked);
    }

    @Test
    @DisplayName("refresh 之內讀的是上次的數字，不重問；過了才重問，數字跟著資料庫變")
    void cachesUntilRefresh() {
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger today = new AtomicInteger(2);
        new ActiveUsersMetrics(windows -> {
            asked.incrementAndGet();
            return List.of(today.get(), 7, 9);
        }, REFRESH, clock).bindTo(registry);

        assertEquals(2, gauge("1d"));
        today.set(4);
        clock.advance(Duration.ofSeconds(59));
        assertEquals(2, gauge("1d"), "還在 refresh 之內");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(4, gauge("1d"));
        assertEquals(2, asked.get());
    }

    @Test
    @DisplayName("反例：資料庫出事時是 NaN（沒有值），不是 0 個人；refresh 之內也不再重撞資料庫")
    void failureIsNaNNotZero() {
        AtomicInteger asked = new AtomicInteger();
        new ActiveUsersMetrics(windows -> {
            asked.incrementAndGet();
            throw new IllegalStateException("資料庫連不上");
        }, REFRESH, clock).bindTo(registry);

        assertTrue(Double.isNaN(gauge("1d")));
        assertTrue(Double.isNaN(gauge("7d")));
        assertEquals(1, asked.get());
    }

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-17T04:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
