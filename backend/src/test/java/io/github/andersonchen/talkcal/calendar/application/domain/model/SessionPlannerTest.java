package io.github.andersonchen.talkcal.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SessionPlanner（#261 在空檔裡排時段）")
class SessionPlannerTest {

    private static final Duration TWO_HOURS = Duration.ofHours(2);

    private static FreeSlot slot(int day, int fromHour, int fromMinute, int toHour, int toMinute) {
        return new FreeSlot(LocalDateTime.of(2026, 10, day, fromHour, fromMinute), LocalDateTime.of(2026, 10, day, toHour, toMinute));
    }

    @Test
    @DisplayName("每天一段，放在當天最早放得下的空檔")
    void onePerDayInEarliestSlotThatFits() {
        List<FreeSlot> free = List.of(slot(7, 8, 0, 9, 0), slot(7, 10, 0, 18, 0), slot(7, 19, 0, 22, 0), slot(8, 8, 0, 22, 0));

        assertEquals(List.of(slot(7, 10, 0, 12, 0), slot(8, 8, 0, 10, 0)), SessionPlanner.plan(free, TWO_HOURS));
    }

    @Test
    @DisplayName("空檔從「現在」開始（11:37）：開始時間進位到 12:00")
    void startRoundsUpToHalfHour() {
        assertEquals(List.of(slot(6, 12, 0, 14, 0)), SessionPlanner.plan(List.of(slot(6, 11, 37, 22, 0)), TWO_HOURS));
        assertEquals(LocalDateTime.of(2026, 10, 6, 11, 30), SessionPlanner.roundUpToHalfHour(LocalDateTime.of(2026, 10, 6, 11, 1)));
        assertEquals(LocalDateTime.of(2026, 10, 6, 11, 30), SessionPlanner.roundUpToHalfHour(LocalDateTime.of(2026, 10, 6, 11, 30)));
    }

    @Test
    @DisplayName("反例：空檔不夠長（含進位後不夠）就不排，不會排出超過空檔的時段；那天都放不下就跳過")
    void tooShortSlotsAreSkipped() {
        List<FreeSlot> free = List.of(slot(7, 8, 0, 9, 30), slot(7, 20, 10, 22, 0), slot(8, 14, 0, 16, 0));

        assertEquals(List.of(slot(8, 14, 0, 16, 0)), SessionPlanner.plan(free, TWO_HOURS));
    }

    @Test
    @DisplayName("沒有空檔：回空的；長度不是正的：IllegalArgumentException")
    void edges() {
        assertEquals(List.of(), SessionPlanner.plan(List.of(), TWO_HOURS));
        assertThrows(IllegalArgumentException.class, () -> SessionPlanner.plan(List.of(), Duration.ZERO));
    }
}
