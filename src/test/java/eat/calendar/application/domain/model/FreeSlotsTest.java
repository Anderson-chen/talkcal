package eat.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FreeSlots")
class FreeSlotsTest {

    private static final LocalDate WED = LocalDate.of(2026, 10, 7);
    private static final DateRange WED_ONLY = new DateRange(WED, WED.plusDays(1));
    private static final DateRange WED_THU = new DateRange(WED, WED.plusDays(2));
    // 「現在」在這些日子之前，不影響結果
    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 10, 6, 11, 30);
    private static final Duration HOUR = Duration.ofHours(1);

    private static CalendarEvent event(int day, int fromHour, int fromMinute, int toHour, int toMinute) {
        return new CalendarEvent("事", LocalDateTime.of(2026, 10, day, fromHour, fromMinute), LocalDateTime.of(2026, 10, day, toHour, toMinute));
    }

    private static FreeSlot slot(int day, int fromHour, int fromMinute, int toHour, int toMinute) {
        return new FreeSlot(LocalDateTime.of(2026, 10, day, fromHour, fromMinute), LocalDateTime.of(2026, 10, day, toHour, toMinute));
    }

    @Test
    @DisplayName("沒有行程：整個時段都是空檔，每天一段")
    void emptyDays() {
        assertEquals(List.of(slot(7, 12, 0, 18, 0), slot(8, 12, 0, 18, 0)),
                FreeSlots.find(WED_THU, DayPeriod.AFTERNOON, HOUR, List.of(), EARLIER));
    }

    @Test
    @DisplayName("行程把時段切開：前後兩段")
    void eventSplitsThePeriod() {
        List<FreeSlot> slots = FreeSlots.find(WED_ONLY, DayPeriod.AFTERNOON, HOUR, List.of(event(7, 14, 0, 15, 30)), EARLIER);

        assertEquals(List.of(slot(7, 12, 0, 14, 0), slot(7, 15, 30, 18, 0)), slots);
    }

    @Test
    @DisplayName("太短的不算：只空 30 分鐘、要找一小時的就跳過")
    void tooShortIsSkipped() {
        List<FreeSlot> slots = FreeSlots.find(WED_ONLY, DayPeriod.AFTERNOON, HOUR,
                List.of(event(7, 12, 30, 15, 0), event(7, 15, 30, 18, 0)), EARLIER);

        assertEquals(List.of(), slots);
    }

    @Test
    @DisplayName("時段外的行程不影響；跨進時段的照樣扣掉")
    void eventsOutsideOrStraddling() {
        List<FreeSlot> slots = FreeSlots.find(WED_ONLY, DayPeriod.AFTERNOON, HOUR,
                List.of(event(7, 9, 0, 10, 0), event(7, 11, 0, 13, 0), event(7, 17, 0, 20, 0)), EARLIER);

        assertEquals(List.of(slot(7, 13, 0, 17, 0)), slots);
    }

    @Test
    @DisplayName("重疊的行程：一起扣，不會留下假的縫")
    void overlappingEvents() {
        List<FreeSlot> slots = FreeSlots.find(WED_ONLY, DayPeriod.AFTERNOON, HOUR,
                List.of(event(7, 13, 0, 15, 0), event(7, 14, 0, 14, 30)), EARLIER);

        assertEquals(List.of(slot(7, 12, 0, 13, 0), slot(7, 15, 0, 18, 0)), slots);
    }

    @Test
    @DisplayName("前一晚跨夜到隔天早上的行程，擋到隔天早上的時段")
    void overnightEventBlocksNextMorning() {
        CalendarEvent overnight = new CalendarEvent("夜車", LocalDateTime.of(2026, 10, 6, 23, 0), LocalDateTime.of(2026, 10, 7, 9, 30));

        assertEquals(List.of(slot(7, 9, 30, 12, 0)), FreeSlots.find(WED_ONLY, DayPeriod.MORNING, HOUR, List.of(overnight), EARLIER));
    }

    @Test
    @DisplayName("已經過去的時間不算：今天下午三點問，從 15:00 開始算")
    void pastTimeIsNotFree() {
        LocalDateTime threePm = LocalDateTime.of(2026, 10, 7, 15, 0);

        assertEquals(List.of(slot(7, 15, 0, 18, 0)), FreeSlots.find(WED_ONLY, DayPeriod.AFTERNOON, HOUR, List.of(), threePm));
        // 時段整個過去了：沒有空檔
        assertEquals(List.of(), FreeSlots.find(WED_ONLY, DayPeriod.MORNING, HOUR, List.of(), threePm));
    }

    @Test
    @DisplayName("各時段的範圍：早上 08–12、下午 12–18、晚上 18–22、不限 08–22")
    void periods() {
        assertEquals(List.of(slot(7, 8, 0, 12, 0)), FreeSlots.find(WED_ONLY, DayPeriod.MORNING, HOUR, List.of(), EARLIER));
        assertEquals(List.of(slot(7, 18, 0, 22, 0)), FreeSlots.find(WED_ONLY, DayPeriod.EVENING, HOUR, List.of(), EARLIER));
        assertEquals(List.of(slot(7, 8, 0, 22, 0)), FreeSlots.find(WED_ONLY, DayPeriod.ANYTIME, HOUR, List.of(), EARLIER));
    }

    @Test
    @DisplayName("最短長度不是正的：拒絕")
    void rejectsNonPositiveMinimum() {
        assertThrows(IllegalArgumentException.class, () -> FreeSlots.find(WED_ONLY, DayPeriod.AFTERNOON, Duration.ZERO, List.of(), EARLIER));
        assertThrows(IllegalArgumentException.class, () -> FreeSlots.find(WED_ONLY, DayPeriod.AFTERNOON, null, List.of(), EARLIER));
    }

    @Test
    @DisplayName("FreeSlot 本身：結束要晚於開始")
    void freeSlotRule() {
        LocalDateTime noon = LocalDateTime.of(2026, 10, 7, 12, 0);

        assertThrows(IllegalArgumentException.class, () -> new FreeSlot(noon, noon));
        assertEquals(Duration.ofMinutes(90), new FreeSlot(noon, noon.plusMinutes(90)).length());
    }
}
