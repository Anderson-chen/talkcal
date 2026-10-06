package eat.calendar.adapter.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DateTable")
class DateTableTest {

    @Test
    @DisplayName("從這週一起連續三週，每天一列")
    void coversThreeWeeksFromMonday() {
        List<String> rows = DateTable.of(LocalDate.of(2026, 10, 7)).lines().toList();

        assertEquals(21, rows.size());
        assertTrue(rows.getFirst().startsWith("2026-10-05 = 這週一"));
        assertTrue(rows.getLast().startsWith("2026-10-25 = 下下週日"));
    }

    @Test
    @DisplayName("「下週三」那一列真的是星期三 —— 實測時模型自己算錯的就是這個")
    void nextWednesdayIsAWednesday() {
        String table = DateTable.of(LocalDate.of(2026, 10, 5));

        assertTrue(table.contains("2026-10-14 = 下週三"));
    }

    @Test
    @DisplayName("標出今天、明天、後天")
    void marksTodayTomorrowAndTheDayAfter() {
        String table = DateTable.of(LocalDate.of(2026, 10, 7));

        assertTrue(table.contains("2026-10-07 = 這週三 = 今天"));
        assertTrue(table.contains("2026-10-08 = 這週四 = 明天"));
        assertTrue(table.contains("2026-10-09 = 這週五 = 後天"));
        // 今天以前的日子不標
        assertTrue(table.contains("2026-10-06 = 這週二\n"));
    }

    @Test
    @DisplayName("今天是星期日：一週從星期一開始，所以「明天」是下週一")
    void sundayBelongsToTheWeekThatStartedOnMonday() {
        String table = DateTable.of(LocalDate.of(2026, 10, 11));

        assertTrue(table.contains("2026-10-11 = 這週日 = 今天"));
        assertTrue(table.contains("2026-10-12 = 下週一 = 明天"));
    }
}
