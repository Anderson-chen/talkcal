package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ListArchivedEventsService")
class ListArchivedEventsServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final LocalDate MAR_1 = LocalDate.of(2024, 3, 1);
    private static final LocalDate MAR_5 = LocalDate.of(2024, 3, 5);

    private static ScheduledEvent event(String title, LocalDateTime start, LocalDateTime end) {
        return ScheduledEvent.schedule(new CalendarEvent(title, start, end));
    }

    /** 假的歸檔：記下被要了哪幾個月，回固定的那幾筆（不照月份挑，挑重疊是 service 的事）。 */
    private static class FakeArchive implements EventArchivePort {

        final List<String> asked = new ArrayList<>();
        final List<ScheduledEvent> stored;

        FakeArchive(List<ScheduledEvent> stored) {
            this.stored = stored;
        }

        @Override
        public void store(OwnerId owner, YearMonth month, List<ScheduledEvent> events) {
            fail("查看不該寫歸檔");
        }

        @Override
        public List<ScheduledEvent> load(OwnerId owner, YearMonth firstMonth, YearMonth lastMonth) {
            asked.add(owner + " " + firstMonth + "~" + lastMonth);
            return stored;
        }

        @Override
        public int deleteAll(OwnerId owner) {
            return fail("查看不該刪歸檔");
        }
    }

    @Test
    @DisplayName("只留跟範圍重疊的，依開始時間排；向歸檔要 from 那個月到 to 的下一個月")
    void keepsOverlappingEventsInCalendarOrder() {
        ScheduledEvent later = event("下午", MAR_5.atTime(15, 0), MAR_5.atTime(16, 0));
        ScheduledEvent earlier = event("早上", MAR_1.atTime(9, 0), MAR_1.atTime(10, 0));
        ScheduledEvent crossMonth = event("跨月出差", LocalDateTime.of(2024, 3, 4, 9, 0), LocalDateTime.of(2024, 4, 2, 18, 0));
        ScheduledEvent before = event("範圍前", LocalDateTime.of(2024, 2, 28, 9, 0), LocalDateTime.of(2024, 2, 28, 10, 0));
        ScheduledEvent after = event("範圍後", LocalDateTime.of(2024, 3, 6, 0, 0), LocalDateTime.of(2024, 3, 6, 1, 0));
        // 剛好在範圍開始那一刻結束：不算重疊
        ScheduledEvent touching = event("前一晚到半夜", LocalDateTime.of(2024, 2, 29, 22, 0), MAR_1.atStartOfDay());
        FakeArchive archive = new FakeArchive(List.of(later, before, crossMonth, touching, after, earlier));

        List<ScheduledEvent> found = new ListArchivedEventsService(archive).listArchivedEvents(OWNER, MAR_1, MAR_5);

        assertEquals(List.of(earlier, crossMonth, later), found);
        assertEquals(List.of(OWNER + " 2024-03~2024-04"), archive.asked);
    }

    @Test
    @DisplayName("開始時間一樣的，依結束時間、再依 id 排：每次查順序都一樣")
    void sameStartHasStableOrder() {
        ScheduledEvent longer = event("長", MAR_1.atTime(9, 0), MAR_1.atTime(12, 0));
        ScheduledEvent shorter = event("短", MAR_1.atTime(9, 0), MAR_1.atTime(10, 0));

        ListArchivedEventsService service = new ListArchivedEventsService(new FakeArchive(List.of(longer, shorter)));

        assertEquals(List.of(shorter, longer), service.listArchivedEvents(OWNER, MAR_1, MAR_1));
    }

    @Test
    @DisplayName("反例：擁有者是 null、範圍倒過來、超過 24 個月時拒絕，而且不會碰物件儲存")
    void rejectsBadRangeBeforeLoading() {
        EventArchivePort mustNotLoad = new FakeArchive(List.of()) {
            @Override
            public List<ScheduledEvent> load(OwnerId owner, YearMonth firstMonth, YearMonth lastMonth) {
                return fail("不合規的範圍不該讀歸檔");
            }
        };
        ListArchivedEventsService service = new ListArchivedEventsService(mustNotLoad);

        assertThrows(IllegalArgumentException.class, () -> service.listArchivedEvents(null, MAR_1, MAR_5));
        assertThrows(IllegalArgumentException.class, () -> service.listArchivedEvents(OWNER, MAR_5, MAR_1));
        assertThrows(IllegalArgumentException.class, () -> service.listArchivedEvents(OWNER, MAR_1, MAR_1.plusYears(2)));
    }

    @Test
    @DisplayName("物件儲存出事（IllegalStateException）原樣往外丟，由 controller 翻成 502")
    void storageFailurePropagates() {
        EventArchivePort broken = new FakeArchive(List.of()) {
            @Override
            public List<ScheduledEvent> load(OwnerId owner, YearMonth firstMonth, YearMonth lastMonth) {
                throw new IllegalStateException("歸檔讀取失敗");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new ListArchivedEventsService(broken).listArchivedEvents(OWNER, MAR_1, MAR_5));
    }
}
