package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DayPeriod;
import io.github.andersonchen.talkcal.calendar.application.domain.model.FreeSlot;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlanSessionsService（#261）")
class PlanSessionsServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final LocalDate WED = LocalDate.of(2026, 10, 7);
    private static final Duration TWO_HOURS = Duration.ofHours(2);

    private final List<Object[]> calls = new ArrayList<>();

    private PlanSessionsService service(List<FreeSlot> free) {
        return new PlanSessionsService((owner, start, end, period, minimum) -> {
            calls.add(new Object[] {owner, start, end, period, minimum});
            return free;
        });
    }

    @Test
    @DisplayName("找這個人、這段期間、這個時段裡至少一段長的空檔，每天挑一段")
    void findsSlotsAsLongAsASessionThenPicksOnePerDay() {
        List<FreeSlot> free = List.of(
                new FreeSlot(LocalDateTime.of(2026, 10, 7, 13, 0), LocalDateTime.of(2026, 10, 7, 18, 0)),
                new FreeSlot(LocalDateTime.of(2026, 10, 8, 12, 0), LocalDateTime.of(2026, 10, 8, 18, 0)));

        List<FreeSlot> planned = service(free).planSessions(OWNER, WED, WED.plusDays(2), DayPeriod.AFTERNOON, TWO_HOURS);

        assertEquals(List.of(new FreeSlot(LocalDateTime.of(2026, 10, 7, 13, 0), LocalDateTime.of(2026, 10, 7, 15, 0)),
                new FreeSlot(LocalDateTime.of(2026, 10, 8, 12, 0), LocalDateTime.of(2026, 10, 8, 14, 0))), planned);
        assertEquals(List.of(OWNER, WED, WED.plusDays(2), DayPeriod.AFTERNOON, TWO_HOURS), List.of(calls.getFirst()));
    }

    @Test
    @DisplayName("反例：超過 14 天 → IllegalArgumentException，連空檔都不查；剛好 14 天可以")
    void rejectsMoreThanMaxDays() {
        PlanSessionsService service = service(List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.planSessions(OWNER, WED, WED.plusDays(15), DayPeriod.ANYTIME, TWO_HOURS));
        assertTrue(e.getMessage().contains("最多排 14 天"), e.getMessage());
        assertTrue(calls.isEmpty());

        service.planSessions(OWNER, WED, WED.plusDays(14), DayPeriod.ANYTIME, TWO_HOURS);
        assertEquals(1, calls.size());
    }
}
