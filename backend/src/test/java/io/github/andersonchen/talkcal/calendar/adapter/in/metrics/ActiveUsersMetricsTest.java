package io.github.andersonchen.talkcal.calendar.adapter.in.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ActiveUsersMetrics")
class ActiveUsersMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double gauge(String window) {
        return registry.get("talkcal.active.users").tag("window", window).gauge().value();
    }

    @Test
    @DisplayName("三個期間各一條 gauge，讀的當下才問 use case（數字跟著資料庫變）")
    void readsUseCaseOnEveryScrape() {
        List<Duration> asked = new ArrayList<>();
        AtomicInteger today = new AtomicInteger(2);
        new ActiveUsersMetrics(window -> {
            asked.add(window);
            return window.toDays() == 1 ? today.get() : today.get() + 5;
        }).bindTo(registry);

        assertEquals(2, gauge("1d"));
        assertEquals(7, gauge("7d"));
        assertEquals(7, gauge("30d"));
        today.set(4);
        assertEquals(4, gauge("1d"));
        assertEquals(List.of(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(1)), asked);
    }

    @Test
    @DisplayName("反例：資料庫出事時是 NaN（沒有值），不是 0 個人")
    void failureIsNaNNotZero() {
        new ActiveUsersMetrics(window -> {
            throw new IllegalStateException("資料庫連不上");
        }).bindTo(registry);

        assertTrue(Double.isNaN(gauge("1d")));
    }
}
