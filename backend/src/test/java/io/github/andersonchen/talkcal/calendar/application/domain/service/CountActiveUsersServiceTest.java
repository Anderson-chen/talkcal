package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CountActiveUsersService")
class CountActiveUsersServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T04:00:00Z");

    @Test
    @DisplayName("「最近一天」換成「現在減一天」交給 port，回 port 數到的人數")
    void asksPortSinceNowMinusWindow() {
        List<Instant> asked = new ArrayList<>();
        CountActiveUsersService service = new CountActiveUsersService(since -> {
            asked.add(since);
            return 3;
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(3, service.countActiveUsers(Duration.ofDays(1)));
        assertEquals(List.of(Instant.parse("2026-10-16T04:00:00Z")), asked);
    }

    @Test
    @DisplayName("期間是 null、0、負的：IllegalArgumentException，不查資料庫")
    void rejectsNonPositiveWindow() {
        CountActiveUsersService service = new CountActiveUsersService(since -> {
            throw new AssertionError("不該查");
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(null));
        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(Duration.ofDays(-1)));
    }
}
