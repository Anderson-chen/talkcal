package io.github.andersonchen.talkcal.app.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.RustFsContainer;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.S3EventArchiveAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ArchiveEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.RestoreEventsService;
import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 行程歸檔的整條路（#54）：真的 PostgreSQL、真的 S3 相容儲存（RustFS），中間是正式的 use case 和 adapter，跟 app 裡接的一樣。
 *
 * 每個規則都配一個反例：
 * - 舊的搬走 ↔ 不舊的（昨天、剛好在期限上）留著
 * - 存好才刪 ↔ 上傳失敗時資料庫一筆都沒少（整個儲存掛掉、只有某一包失敗都是）
 * - 歸檔 → 還原後跟原本一模一樣 ↔ 還原兩次不會多一份、不蓋掉線上的
 * - 只還原得到自己的 ↔ 別人還原同一段月份拿不到我的
 */
@Testcontainers
@DisplayName("行程歸檔：PostgreSQL ↔ S3 相容儲存")
class EventArchiveFlowTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @Container
    static final RustFsContainer RUSTFS = new RustFsContainer();

    private static final OwnerId ALICE = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000a");
    private static final OwnerId BOB = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000b");

    // 「現在」固定在 2026-10-19，保留 2 年 → 2024-10-19 之前結束的算舊
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2024, 10, 19, 4, 30);

    static JdbcClient jdbc;
    static JdbcCalendarEventAdapter events;
    static ArchiveBucket bucket;
    static S3EventArchiveAdapter archive;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        events = new JdbcCalendarEventAdapter(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        RUSTFS.createBucket("talkcal-archive");
        bucket = RUSTFS.bucket("talkcal-archive");
        archive = new S3EventArchiveAdapter(bucket, Clock.systemUTC());
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM calendar_event").update();
        archive.deleteAll(ALICE);
        archive.deleteAll(BOB);
    }

    private static ArchiveEventsUseCase archiving(EventArchivePort to) {
        return new ArchiveEventsService(events, to, events, 500);
    }

    private static ScheduledEvent at(String title, LocalDateTime start) {
        return new ScheduledEvent(EventId.newId(), new CalendarEvent(title, start, start.plusHours(1)));
    }

    // 那個人在資料庫裡的全部行程，依結束時間排（月曆的 load 一次最多 100 天，這裡借「找舊行程」那句把期限開到很遠）
    private static List<ScheduledEvent> online(OwnerId owner) {
        return events.loadEndedBefore(LocalDateTime.of(2100, 1, 1, 0, 0), 1000).getOrDefault(owner, List.of());
    }

    @Test
    @DisplayName("結束早於期限的搬走（資料庫少了、歸檔多了）；昨天的、剛好在期限那一刻結束的留著")
    void archivesOnlyOldEvents() {
        ScheduledEvent old = at("三年前", LocalDateTime.of(2023, 5, 1, 9, 0));
        ScheduledEvent yesterday = at("昨天", LocalDateTime.of(2026, 10, 18, 9, 0));
        // 結束時間剛好等於 cutoff：不算「早於」，留著
        ScheduledEvent onTheLine = new ScheduledEvent(EventId.newId(),
                new CalendarEvent("剛好在期限上", CUTOFF.minusHours(1), CUTOFF));
        events.save(ALICE, List.of(old, yesterday, onTheLine));

        ArchiveEventsUseCase.Report report = archiving(archive).archiveEndedBefore(CUTOFF);

        assertEquals(new ArchiveEventsUseCase.Report(1, 0), report);
        assertEquals(List.of(onTheLine, yesterday), online(ALICE));
        assertEquals(List.of(old), archive.load(ALICE, new MonthRange(YearMonth.of(2023, 5), YearMonth.of(2023, 5))));
    }

    @Test
    @DisplayName("反例：物件儲存掛了（bucket 不存在）→ 一筆都不刪，資料庫跟歸檔前一模一樣")
    void uploadFailureDeletesNothing() {
        ScheduledEvent old = at("三年前", LocalDateTime.of(2023, 5, 1, 9, 0));
        ScheduledEvent older = at("四年前", LocalDateTime.of(2022, 5, 1, 9, 0));
        events.save(ALICE, List.of(old, older));
        S3EventArchiveAdapter broken = new S3EventArchiveAdapter(RUSTFS.bucket("no-such-bucket"), Clock.systemUTC());

        ArchiveEventsUseCase.Report report = archiving(broken).archiveEndedBefore(CUTOFF);

        assertEquals(new ArchiveEventsUseCase.Report(0, 2), report);
        assertEquals(List.of(older, old), online(ALICE));
    }

    @Test
    @DisplayName("反例：只有 Bob 那一包上傳失敗 → Bob 的一筆都沒少，Alice 的照常搬走（一包失敗只影響那一包）")
    void oneFailedGroupKeepsOnlyThatGroup() {
        ScheduledEvent alices = at("Alice 的", LocalDateTime.of(2023, 5, 1, 9, 0));
        ScheduledEvent bobs = at("Bob 的", LocalDateTime.of(2023, 5, 1, 9, 0));
        events.save(ALICE, List.of(alices));
        events.save(BOB, List.of(bobs));
        EventArchivePort failsForBob = new EventArchivePort() {
            @Override
            public void store(OwnerId owner, YearMonth month, List<ScheduledEvent> scheduled) {
                if (owner.equals(BOB)) {
                    throw new IllegalStateException("模擬：上傳到一半斷線");
                }
                archive.store(owner, month, scheduled);
            }

            @Override
            public List<ScheduledEvent> load(OwnerId owner, MonthRange months) {
                return archive.load(owner, months);
            }

            @Override
            public int deleteAll(OwnerId owner) {
                return archive.deleteAll(owner);
            }
        };

        ArchiveEventsUseCase.Report report = archiving(failsForBob).archiveEndedBefore(CUTOFF);

        assertEquals(new ArchiveEventsUseCase.Report(1, 1), report);
        assertTrue(online(ALICE).isEmpty());
        assertEquals(List.of(bobs), online(BOB));
        assertTrue(archive.load(BOB, new MonthRange(YearMonth.of(2023, 5), YearMonth.of(2023, 5))).isEmpty());
    }

    @Test
    @DisplayName("歸檔 → 還原：放回來的跟原本一模一樣（id、分類、地點、備註都在）；還原兩次不會多一份")
    void archiveThenRestoreIsLossless() {
        ScheduledEvent detailed = new ScheduledEvent(EventId.newId(), new CalendarEvent("看牙醫",
                LocalDateTime.of(2023, 5, 1, 9, 0), LocalDateTime.of(2023, 5, 1, 10, 30),
                Category.HEALTH, Optional.of("仁愛路診所"), Optional.of("帶健保卡")));
        ScheduledEvent plain = at("開會", LocalDateTime.of(2023, 6, 2, 14, 0));
        events.save(ALICE, List.of(detailed, plain));
        List<ScheduledEvent> before = online(ALICE);
        archiving(archive).archiveEndedBefore(CUTOFF);
        assertTrue(online(ALICE).isEmpty());

        RestoreEventsService restore = new RestoreEventsService(archive, events);
        assertEquals(2, restore.restore(ALICE, new MonthRange(YearMonth.of(2023, 5), YearMonth.of(2023, 6))));
        assertEquals(before, online(ALICE));

        assertEquals(0, restore.restore(ALICE, new MonthRange(YearMonth.of(2023, 5), YearMonth.of(2023, 6))));
        assertEquals(before, online(ALICE));
    }

    @Test
    @DisplayName("不同使用者不混：Bob 還原同一段月份拿不到 Alice 的；Alice 還原也不會帶出 Bob 的")
    void restoreOnlyReturnsOwnersEvents() {
        ScheduledEvent alices = at("Alice 的", LocalDateTime.of(2023, 5, 1, 9, 0));
        ScheduledEvent bobs = at("Bob 的", LocalDateTime.of(2023, 5, 3, 9, 0));
        events.save(ALICE, List.of(alices));
        events.save(BOB, List.of(bobs));
        archiving(archive).archiveEndedBefore(CUTOFF);
        RestoreEventsService restore = new RestoreEventsService(archive, events);

        assertEquals(1, restore.restore(BOB, new MonthRange(YearMonth.of(2023, 5), YearMonth.of(2023, 5))));
        assertEquals(List.of(bobs), online(BOB));
        assertTrue(online(ALICE).isEmpty());

        assertEquals(1, restore.restore(ALICE, new MonthRange(YearMonth.of(2023, 5), YearMonth.of(2023, 5))));
        assertEquals(List.of(alices), online(ALICE));
        assertEquals(List.of(bobs), online(BOB));
    }

    @Test
    @DisplayName("舊的比一批還多：一批一批搬，直到全部搬完")
    void archivesInBatches() {
        List<ScheduledEvent> many = IntStream.range(0, 7)
                .mapToObj(i -> at("第 " + i + " 筆", LocalDateTime.of(2023, 1 + i, 1, 9, 0)))
                .toList();
        events.save(ALICE, many);

        ArchiveEventsUseCase.Report report = new ArchiveEventsService(events, archive, events, 3).archiveEndedBefore(CUTOFF);

        assertEquals(new ArchiveEventsUseCase.Report(7, 0), report);
        assertTrue(online(ALICE).isEmpty());
        assertEquals(many, archive.load(ALICE, new MonthRange(YearMonth.of(2023, 1), YearMonth.of(2023, 7))));
    }

    @Test
    @DisplayName("反例：讀出來之後被改成未來的那筆（已經不舊了），歸檔好之後也不刪")
    void eventEditedAfterLoadingIsNotDeleted() {
        ScheduledEvent old = at("三年前", LocalDateTime.of(2023, 5, 1, 9, 0));
        events.save(ALICE, List.of(old));
        // 模擬：排程讀出來之後、刪之前，使用者把它改到下個月
        jdbc.sql("UPDATE calendar_event SET start_at = '2026-11-01 09:00', end_at = '2026-11-01 10:00' WHERE id = :id")
                .param("id", old.id().value()).update();

        assertEquals(0, events.deleteArchived(ALICE, List.of(old)));
        assertEquals(1, online(ALICE).size());
    }

    @Test
    @DisplayName("反例：讀出來之後只被改了備註（還是舊的）也不刪 —— 歸檔裡存的是改之前的，刪了這次修改就不見了；沒改過的照刪")
    void eventWithEditedDetailsIsNotDeleted() {
        ScheduledEvent edited = at("三年前", LocalDateTime.of(2023, 5, 1, 9, 0));
        ScheduledEvent untouched = at("沒動過", LocalDateTime.of(2023, 5, 2, 9, 0));
        events.save(ALICE, List.of(edited, untouched));
        // 模擬：排程讀出來之後、刪之前，使用者補上備註
        jdbc.sql("UPDATE calendar_event SET note = '補上的備註' WHERE id = :id").param("id", edited.id().value()).update();

        assertEquals(1, events.deleteArchived(ALICE, List.of(edited, untouched)));
        List<ScheduledEvent> left = online(ALICE);
        assertEquals(List.of(edited.id()), left.stream().map(ScheduledEvent::id).toList());
        assertEquals(Optional.of("補上的備註"), left.getFirst().event().note());
    }

    @Test
    @DisplayName("還原沒給擁有者或月份範圍：不收（範圍本身的規則在 MonthRangeTest）")
    void restoreArgumentsAreRequired() {
        RestoreEventsService restore = new RestoreEventsService(archive, events);
        MonthRange march = new MonthRange(YearMonth.of(2024, 3), YearMonth.of(2024, 3));

        assertThrows(IllegalArgumentException.class, () -> restore.restore(null, march));
        assertThrows(IllegalArgumentException.class, () -> restore.restore(ALICE, null));
        assertEquals(0, restore.restore(ALICE, march));
    }
}
