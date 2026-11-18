package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.calendar.application.domain.model.GuestDemo;
import io.github.andersonchen.talkcal.calendar.application.port.in.GuestDemoLimitException;
import io.github.andersonchen.talkcal.calendar.application.port.in.ShowGuestDemoUseCase.Shown;
import io.github.andersonchen.talkcal.calendar.application.port.in.SignInRequiredException;
import io.github.andersonchen.talkcal.calendar.application.port.out.GuestDemoUsagePort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShowGuestDemoService")
class ShowGuestDemoServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    // 台北 2026-11-18 23:00，UTC 還是 15:00
    private static final Instant LATE_EVENING = Instant.parse("2026-11-18T15:00:00Z");

    /** 跟 JdbcGuestDemoUsageAdapter 同一個約定：沒到上限加一、到了回 empty */
    static final class InMemoryUsage implements GuestDemoUsagePort {
        final Map<String, Integer> used = new HashMap<>();

        @Override
        public OptionalInt use(String client, LocalDate day, int limit) {
            String key = client + "@" + day;
            int now = used.getOrDefault(key, 0);
            if (now >= limit) {
                return OptionalInt.empty();
            }
            used.put(key, now + 1);
            return OptionalInt.of(now + 1);
        }
    }

    private final InMemoryUsage usage = new InMemoryUsage();
    private final ShowGuestDemoService service = new ShowGuestDemoService(usage, Clock.fixed(LATE_EVENING, TAIPEI));

    @Test
    @DisplayName("同一個 IP 前 3 次都有回答、剩餘次數遞減，第 4 次丟 GuestDemoLimitException")
    void fourthTimeIsRefused() {
        assertEquals(2, service.show("203.0.113.7", GuestDemo.ADD_MEETING).remaining());
        assertEquals(1, service.show("203.0.113.7", GuestDemo.TOMORROW).remaining());
        assertEquals(0, service.show("203.0.113.7", GuestDemo.FREE_AFTERNOONS).remaining());

        assertThrows(GuestDemoLimitException.class, () -> service.show("203.0.113.7", GuestDemo.ADD_MEETING));
    }

    @Test
    @DisplayName("反例：不同 IP 各自算 3 次")
    void eachClientHasItsOwnCount() {
        for (int i = 0; i < 3; i++) {
            service.show("203.0.113.7", GuestDemo.TOMORROW);
        }
        assertThrows(GuestDemoLimitException.class, () -> service.show("203.0.113.7", GuestDemo.TOMORROW));

        assertEquals(2, service.show("198.51.100.4", GuestDemo.TOMORROW).remaining());
    }

    @Test
    @DisplayName("看滿時，要等到台北的午夜（不是 UTC 的）：23:00 看滿，Retry-After 是 1 小時")
    void retryAfterIsLocalMidnight() {
        for (int i = 0; i < 3; i++) {
            service.show("203.0.113.7", GuestDemo.TOMORROW);
        }
        GuestDemoLimitException e = assertThrows(GuestDemoLimitException.class, () -> service.show("203.0.113.7", GuestDemo.TOMORROW));
        assertEquals(Duration.ofHours(1), e.retryAfter());
    }

    @Test
    @DisplayName("回答的「明天」照 calendar.zone 算：台北 23:00 的明天是 11/19，不是 UTC 的同一天")
    void tomorrowFollowsZone() {
        Shown shown = service.show("203.0.113.7", GuestDemo.ADD_MEETING);
        assertEquals(LocalDateTime.of(2026, 11, 19, 15, 0), shown.answer().proposals().getFirst().start());
    }

    @Test
    @DisplayName("反例：自由文字丟 SignInRequiredException、空白的來源丟 IllegalArgumentException，都不算一次")
    void invalidRequestsAreNotCounted() {
        assertThrows(SignInRequiredException.class, () -> service.show("203.0.113.7", "幫我把明天的行程全部刪掉"));
        assertThrows(IllegalArgumentException.class, () -> service.show(" ", GuestDemo.TOMORROW));

        assertEquals(Map.of(), usage.used);
        assertEquals(2, service.show("203.0.113.7", GuestDemo.TOMORROW).remaining());
    }
}
