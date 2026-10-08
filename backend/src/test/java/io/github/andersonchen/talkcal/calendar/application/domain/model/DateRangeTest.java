package io.github.andersonchen.talkcal.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DateRange")
class DateRangeTest {

    private static final LocalDate SEP_28 = LocalDate.of(2026, 9, 28);
    private static final LocalDate NOV_09 = LocalDate.of(2026, 11, 9);

    @Test
    @DisplayName("月曆一頁（42 天）：開始那天 00:00 起，結束那天 00:00 止（不含）")
    void monthPage() {
        DateRange page = new DateRange(SEP_28, NOV_09);

        assertEquals(LocalDateTime.of(2026, 9, 28, 0, 0), page.startTime());
        assertEquals(LocalDateTime.of(2026, 11, 9, 0, 0), page.endTime());
    }

    @Test
    @DisplayName("一天也可以")
    void singleDay() {
        DateRange day = new DateRange(SEP_28, SEP_28.plusDays(1));

        assertEquals(LocalDateTime.of(2026, 9, 29, 0, 0), day.endTime());
    }

    @Test
    @DisplayName("開始或結束是 null 時拒絕")
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new DateRange(null, NOV_09));
        assertThrows(IllegalArgumentException.class, () -> new DateRange(SEP_28, null));
    }

    @Test
    @DisplayName("結束不晚於開始時拒絕（同一天 = 零天，也不行）")
    void rejectsEmptyOrReversed() {
        assertThrows(IllegalArgumentException.class, () -> new DateRange(SEP_28, SEP_28));
        assertThrows(IllegalArgumentException.class, () -> new DateRange(NOV_09, SEP_28));
    }

    @Test
    @DisplayName("剛好上限天數可以，多一天就拒絕")
    void capsLength() {
        new DateRange(SEP_28, SEP_28.plusDays(DateRange.MAX_DAYS));

        assertThrows(IllegalArgumentException.class, () -> new DateRange(SEP_28, SEP_28.plusDays(DateRange.MAX_DAYS + 1)));
    }
}
