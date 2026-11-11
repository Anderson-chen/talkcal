package io.github.andersonchen.talkcal.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ArchivePeriod：查看封存的日期範圍")
class ArchivePeriodTest {

    private static final LocalDate MAR_1 = LocalDate.of(2024, 3, 1);
    private static final LocalDate MAR_5 = LocalDate.of(2024, 3, 5);

    private static CalendarEvent from(LocalDateTime start, LocalDateTime end) {
        return new CalendarEvent("行程", start, end);
    }

    @Test
    @DisplayName("看一天（from = to）、剛好 24 個月都收；from 晚於 to、25 個月、缺一端都不收")
    void rejectsInvalidRanges() {
        new ArchivePeriod(MAR_5, MAR_5);
        new ArchivePeriod(LocalDate.of(2022, 2, 1), LocalDate.of(2024, 1, 31));

        assertThrows(IllegalArgumentException.class, () -> new ArchivePeriod(MAR_5, MAR_1));
        assertThrows(IllegalArgumentException.class, () -> new ArchivePeriod(LocalDate.of(2022, 1, 31), LocalDate.of(2024, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ArchivePeriod(null, MAR_5));
        assertThrows(IllegalArgumentException.class, () -> new ArchivePeriod(MAR_1, null));
    }

    @Test
    @DisplayName("反例：遠到 LocalDate 盡頭的日期是 IllegalArgumentException（400），不是算隔天時才爆的 DateTimeException（500）")
    void farFutureDateIsRejected() {
        new ArchivePeriod(LocalDate.of(9999, 12, 31), LocalDate.of(9999, 12, 31));

        assertThrows(IllegalArgumentException.class, () -> new ArchivePeriod(LocalDate.MAX, LocalDate.MAX));
    }

    @Test
    @DisplayName("兩端都含：from 那天一早、to 那天晚上的都算")
    void bothEndsInclusive() {
        ArchivePeriod period = new ArchivePeriod(MAR_1, MAR_5);

        assertTrue(period.overlaps(from(MAR_1.atTime(0, 0), MAR_1.atTime(1, 0))));
        assertTrue(period.overlaps(from(MAR_5.atTime(23, 0), MAR_5.atTime(23, 59))));
    }

    @Test
    @DisplayName("跟範圍重疊就算：從範圍前開始、到範圍後才結束、整個包住範圍的都算")
    void overlappingEventsCount() {
        ArchivePeriod march5 = new ArchivePeriod(MAR_5, MAR_5);

        assertTrue(march5.overlaps(from(MAR_1.atTime(9, 0), MAR_5.atTime(1, 0))));
        assertTrue(march5.overlaps(from(MAR_5.atTime(22, 0), MAR_5.plusDays(1).atTime(2, 0))));
        assertTrue(march5.overlaps(from(MAR_1.atTime(9, 0), LocalDateTime.of(2024, 4, 20, 9, 0))));
    }

    @Test
    @DisplayName("反例：剛好在 from 00:00 結束、剛好在 to 隔天 00:00 開始的不算；範圍外的不算")
    void touchingTheEdgeIsNotOverlap() {
        ArchivePeriod period = new ArchivePeriod(MAR_1, MAR_5);

        assertFalse(period.overlaps(from(MAR_1.minusDays(1).atTime(22, 0), MAR_1.atStartOfDay())));
        assertFalse(period.overlaps(from(MAR_5.plusDays(1).atStartOfDay(), MAR_5.plusDays(1).atTime(1, 0))));
        assertFalse(period.overlaps(from(LocalDateTime.of(2024, 2, 10, 9, 0), LocalDateTime.of(2024, 2, 10, 10, 0))));
        assertFalse(period.overlaps(from(LocalDateTime.of(2024, 3, 20, 9, 0), LocalDateTime.of(2024, 3, 20, 10, 0))));
    }

    @Test
    @DisplayName("要讀的包：from 那個月 ～ to 的下一個月（跨月的行程依結束的月份分包）")
    void readsOneMonthPastTheEnd() {
        ArchivePeriod period = new ArchivePeriod(LocalDate.of(2024, 1, 15), LocalDate.of(2024, 12, 31));

        assertEquals(YearMonth.of(2024, 1), period.firstEndMonth());
        assertEquals(YearMonth.of(2025, 1), period.lastEndMonth());
    }
}
