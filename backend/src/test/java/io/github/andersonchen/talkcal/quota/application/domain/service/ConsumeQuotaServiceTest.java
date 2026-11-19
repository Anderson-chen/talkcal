package io.github.andersonchen.talkcal.quota.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaBucket;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRule;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRules;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaSubject;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase.Consumed;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase.QuotaRequest;
import io.github.andersonchen.talkcal.quota.application.port.in.QuotaExceededException;
import io.github.andersonchen.talkcal.quota.application.port.out.QuotaCounterPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConsumeQuotaService")
class ConsumeQuotaServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    // 台北 2026-11-18 23:00，UTC 還是 15:00
    private static final Instant LATE_EVENING = Instant.parse("2026-11-18T15:00:00Z");

    private static final QuotaRules RULES = QuotaRules.of(
            List.of(new QuotaRule("POST", "/api/calendar/assistant", "guest", "ask"),
                    new QuotaRule("POST", "/api/auth/guest", "none", "signup"),
                    new QuotaRule("POST", "/api/calendar/assistant", "free", "member-ask")),
            List.of(new QuotaBucket("ask", QuotaSubject.IP, 3, "今天問完了"),
                    new QuotaBucket("signup", QuotaSubject.GLOBAL, 2, "名額滿了"),
                    new QuotaBucket("member-ask", QuotaSubject.ACCOUNT, 20, "今天問完了")));

    /** 跟 RedisQuotaCounterAdapter 同一個約定：加一回傳加完的值；broken 時丟 IllegalStateException */
    static final class InMemoryCounter implements QuotaCounterPort {
        final Map<String, Long> counts = new HashMap<>();
        final Map<String, Instant> expires = new HashMap<>();
        boolean broken;

        @Override
        public long increment(String key, Instant expiresAt) {
            if (broken) {
                throw new IllegalStateException("Redis 無法連線");
            }
            expires.putIfAbsent(key, expiresAt);
            return counts.merge(key, 1L, Long::sum);
        }

        @Override
        public void decrement(String key) {
            counts.computeIfPresent(key, (k, v) -> v - 1);
        }
    }

    private final InMemoryCounter counter = new InMemoryCounter();
    private final AtomicReference<QuotaRules> table = new AtomicReference<>(RULES);
    private final ConsumeQuotaService service = new ConsumeQuotaService(table::get, counter,
            Clock.fixed(LATE_EVENING, TAIPEI), Duration.ZERO);

    private static QuotaRequest guestAsks(String ip) {
        return new QuotaRequest("POST", "/api/calendar/assistant", "guest", ip, "guest-account");
    }

    @Test
    @DisplayName("同一個 IP 前 3 次放行，第 4 次丟 QuotaExceededException，訊息照規則表")
    void fourthIsRefused() {
        for (int i = 0; i < 3; i++) {
            assertTrue(service.consume(guestAsks("203.0.113.7")).isPresent());
        }
        QuotaExceededException e = assertThrows(QuotaExceededException.class, () -> service.consume(guestAsks("203.0.113.7")));
        assertEquals("今天問完了", e.getMessage());
    }

    @Test
    @DisplayName("Retry-After 到台北的午夜（不是 UTC 的）：23:00 用完是 1 小時；key 帶台北的日期")
    void resetsAtLocalMidnight() {
        Optional<Consumed> first = service.consume(guestAsks("203.0.113.7"));
        assertEquals("quota:ask:203.0.113.7:2026-11-18", first.orElseThrow().counterKey());
        service.consume(guestAsks("203.0.113.7"));
        service.consume(guestAsks("203.0.113.7"));
        QuotaExceededException e = assertThrows(QuotaExceededException.class, () -> service.consume(guestAsks("203.0.113.7")));
        assertEquals(Duration.ofHours(1), e.retryAfter());
    }

    @Test
    @DisplayName("反例：不同 IP 各自算；照帳號的計數照帳號分；全站的計數大家共用")
    void subjects() {
        for (int i = 0; i < 3; i++) {
            service.consume(guestAsks("203.0.113.7"));
        }
        assertTrue(service.consume(guestAsks("198.51.100.4")).isPresent());

        QuotaRequest amy = new QuotaRequest("POST", "/api/calendar/assistant", "free", "203.0.113.7", "amy");
        assertEquals("quota:member-ask:amy:2026-11-18", service.consume(amy).orElseThrow().counterKey());

        QuotaRequest signup = new QuotaRequest("POST", "/api/auth/guest", "none", "203.0.113.7", null);
        service.consume(signup);
        service.consume(new QuotaRequest("POST", "/api/auth/guest", "none", "198.51.100.4", null));
        assertThrows(QuotaExceededException.class, () -> service.consume(signup));
    }

    @Test
    @DisplayName("反例：沒有規則對到的請求不限制、不計數")
    void unmatchedIsFree() {
        assertEquals(Optional.empty(), service.consume(new QuotaRequest("GET", "/api/calendar/events", "guest", "1.2.3.4", null)));
        assertTrue(counter.counts.isEmpty());
    }

    @Test
    @DisplayName("退回之後又多一次")
    void refundGivesOneBack() {
        Consumed one = service.consume(guestAsks("203.0.113.7")).orElseThrow();
        service.consume(guestAsks("203.0.113.7"));
        service.consume(guestAsks("203.0.113.7"));
        service.refund(one);
        assertTrue(service.consume(guestAsks("203.0.113.7")).isPresent());
    }

    @Test
    @DisplayName("反例：計數暫時讀不到（Redis 無法連線）時放行")
    void failsOpen() {
        counter.broken = true;
        assertEquals(Optional.empty(), service.consume(guestAsks("203.0.113.7")));
    }

    @Test
    @DisplayName("規則表改了之後重讀就生效；反例：重讀失敗時沿用上一份")
    void rulesAreReloaded() {
        table.set(QuotaRules.NONE);
        assertEquals(Optional.empty(), service.consume(guestAsks("203.0.113.7")));

        table.set(RULES);
        assertTrue(service.consume(guestAsks("203.0.113.7")).isPresent());

        AtomicInteger loads = new AtomicInteger();
        ConsumeQuotaService failing = new ConsumeQuotaService(() -> {
            if (loads.getAndIncrement() > 0) {
                throw new IllegalStateException("資料庫無法連線");
            }
            return RULES;
        }, counter, Clock.fixed(LATE_EVENING, TAIPEI), Duration.ZERO);
        assertTrue(failing.consume(guestAsks("192.0.2.1")).isPresent());
        assertTrue(failing.consume(guestAsks("192.0.2.1")).isPresent());
    }
}
