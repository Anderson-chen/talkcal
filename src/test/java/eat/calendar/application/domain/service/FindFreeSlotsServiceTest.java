package eat.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.DateRange;
import eat.calendar.application.domain.model.DayPeriod;
import eat.calendar.application.domain.model.FreeSlot;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.port.out.LoadEventsPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FindFreeSlotsService")
class FindFreeSlotsServiceTest {

    // 台北 2026-10-07 13:00（UTC 05:00）
    private static final Clock WED_1PM = Clock.fixed(Instant.parse("2026-10-07T05:00:00Z"), ZoneId.of("Asia/Taipei"));
    private static final LocalDate WED = LocalDate.of(2026, 10, 7);

    @Test
    @DisplayName("讀出那幾天的行程交給 FreeSlots 算，「現在」取自時鐘（台北的牆上時間）")
    void loadsAndComputes() {
        List<DateRange> asked = new ArrayList<>();
        LoadEventsPort port = range -> {
            asked.add(range);
            return List.of(ScheduledEvent.schedule(new CalendarEvent("簡報", WED.atTime(15, 0), WED.atTime(16, 0))));
        };

        List<FreeSlot> slots = new FindFreeSlotsService(port, WED_1PM)
                .findFreeSlots(WED, WED.plusDays(1), DayPeriod.AFTERNOON, Duration.ofHours(1));

        assertEquals(List.of(new DateRange(WED, WED.plusDays(1))), asked);
        // 12:00–13:00 已經過去了，所以從 13:00 開始
        assertEquals(List.of(new FreeSlot(WED.atTime(13, 0), WED.atTime(15, 0)), new FreeSlot(WED.atTime(16, 0), WED.atTime(18, 0))), slots);
    }

    @Test
    @DisplayName("期間不合規：拒絕，而且不碰資料庫")
    void rejectsBadRange() {
        LoadEventsPort mustNotLoad = range -> fail("不該碰資料庫");

        assertThrows(IllegalArgumentException.class, () -> new FindFreeSlotsService(mustNotLoad, WED_1PM)
                .findFreeSlots(WED, WED, DayPeriod.AFTERNOON, Duration.ofHours(1)));
    }

    @Test
    @DisplayName("沒有指定現在之前的日子：照樣算（不會因為時鐘而丟例外）")
    void futureDays() {
        List<FreeSlot> slots = new FindFreeSlotsService(range -> List.of(), WED_1PM)
                .findFreeSlots(WED.plusDays(1), WED.plusDays(2), DayPeriod.MORNING, Duration.ofMinutes(30));

        assertEquals(List.of(new FreeSlot(LocalDateTime.of(2026, 10, 8, 8, 0), LocalDateTime.of(2026, 10, 8, 12, 0))), slots);
    }
}
