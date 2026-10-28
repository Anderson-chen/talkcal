package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CountActiveUsersService")
class CountActiveUsersServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T04:00:00Z");

    @Test
    @DisplayName("每個「最近 N 天」換成「現在減 N 天」，一次交給 port，照順序回 port 數到的人數")
    void asksPortOnceForAllWindows() {
        List<List<Instant>> asked = new ArrayList<>();
        CountActiveUsersService service = new CountActiveUsersService(sinces -> {
            asked.add(sinces);
            return List.of(3, 5);
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(List.of(3, 5), service.countActiveUsers(List.of(Duration.ofDays(1), Duration.ofDays(7))));
        assertEquals(List.of(List.of(Instant.parse("2026-10-16T04:00:00Z"), Instant.parse("2026-10-10T04:00:00Z"))), asked);
    }

    @Test
    @DisplayName("期間清單是 null、空的，或有 null、0、負的期間：IllegalArgumentException，不查資料庫")
    void rejectsBadWindows() {
        CountActiveUsersService service = new CountActiveUsersService(sinces -> {
            throw new AssertionError("不該查");
        }, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(null));
        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(Arrays.asList(Duration.ofDays(1), null)));
        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(List.of(Duration.ZERO)));
        assertThrows(IllegalArgumentException.class, () -> service.countActiveUsers(List.of(Duration.ofDays(-1))));
    }
}
