package io.github.andersonchen.talkcal.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MonthRange：對話還原的月份範圍")
class MonthRangeTest {

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
