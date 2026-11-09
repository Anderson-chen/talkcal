package io.github.andersonchen.talkcal.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MonthRange：還原的月份範圍")
class MonthRangeTest {

    @Test
    @DisplayName("兩端都含：from、to 那兩個月都算，範圍外的不算")
    void bothEndsInclusive() {
        MonthRange range = new MonthRange(YearMonth.of(2024, 1), YearMonth.of(2024, 3));

        assertTrue(range.contains(YearMonth.of(2024, 1)));
        assertTrue(range.contains(YearMonth.of(2024, 3)));
        assertFalse(range.contains(YearMonth.of(2023, 12)));
        assertFalse(range.contains(YearMonth.of(2024, 4)));
    }

    @Test
    @DisplayName("剛好 24 個月收；from 晚於 to、25 個月、缺一端都不收")
    void rejectsInvalidRanges() {
        new MonthRange(YearMonth.of(2022, 2), YearMonth.of(2024, 1));
        new MonthRange(YearMonth.of(2024, 1), YearMonth.of(2024, 1));

        assertThrows(IllegalArgumentException.class, () -> new MonthRange(YearMonth.of(2024, 2), YearMonth.of(2024, 1)));
        assertThrows(IllegalArgumentException.class, () -> new MonthRange(YearMonth.of(2022, 1), YearMonth.of(2024, 1)));
        assertThrows(IllegalArgumentException.class, () -> new MonthRange(null, YearMonth.of(2024, 1)));
        assertThrows(IllegalArgumentException.class, () -> new MonthRange(YearMonth.of(2024, 1), null));
    }
}
