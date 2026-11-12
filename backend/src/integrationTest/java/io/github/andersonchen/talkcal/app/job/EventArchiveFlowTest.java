package io.github.andersonchen.talkcal.app.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.RustFsContainer;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.S3EventArchiveAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ArchiveEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ListArchivedEventsService;
import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListArchivedEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;

import java.time.Clock;
import java.time.LocalDate;
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
 * 行程歸檔的整條路（#54、#274）：真的 PostgreSQL、真的 S3 相容儲存（RustFS），中間是正式的 use case 和 adapter，跟 app 裡接的一樣。
 *
 * 每個規則都配一個反例：
 * - 舊的搬走 ↔ 不舊的（昨天、剛好在期限上）留著
 * - 存好才刪 ↔ 上傳失敗時資料庫一筆都沒少（整個儲存掛掉、只有某一包失敗都是）
 * - 歸檔 → 查看跟原本一模一樣 ↔ 查看不寫資料庫，查完線上的筆數不變
 * - 跨月的行程查得到 ↔ 範圍外的、剛好碰到邊界的不出現
 * - 只查得到自己的 ↔ 別人查同一段期間拿不到我的
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
    private static final YearMonth MAY_2023 = YearMonth.of(2023, 5);

    static JdbcClient jdbc;
    static JdbcCalendarEventAdapter events;
    static ArchiveBucket bucket;
    static S3EventArchiveAdapter archive;
    static ListArchivedEventsUseCase viewing;

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
        viewing = new ListArchivedEventsService(archive);
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

    private static ScheduledEvent between(String title, LocalDateTime start, LocalDateTime end) {
        return new ScheduledEvent(EventId.newId(), new CalendarEvent(title, start, end));
    }

    // 那個人在資料庫裡的全部行程，依結束時間排（月曆的 load 一次最多 100 天，這裡借「找舊行程」那句把期限開到很遠）
    private static List<ScheduledEvent> online(OwnerId owner) {
        return events.loadEndedBefore(LocalDateTime.of(2100, 1, 1, 0, 0), 1000).getOrDefault(owner, List.of());
    }

    // 資料庫裡全部的行程筆數，不分人
    private static long onlineRows() {
        return jdbc.sql("SELECT count(*) FROM calendar_event").query(Long.class).single();
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
        assertEquals(List.of(old), archive.load(ALICE, MAY_2023, MAY_2023));
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
            public List<ScheduledEvent> load(OwnerId owner, YearMonth firstMonth, YearMonth lastMonth) {
                return archive.load(owner, firstMonth, lastMonth);
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
        assertTrue(archive.load(BOB, MAY_2023, MAY_2023).isEmpty());
    }

    @Test
    @DisplayName("歸檔 → 照一天、一段期間查看：每個欄位都跟封存前一樣（id、標題、時間、分類、地點、備註）；查完線上的筆數不變")
    void viewIsLosslessAndReadOnly() {
        ScheduledEvent detailed = new ScheduledEvent(EventId.newId(), new CalendarEvent("看牙醫",
                LocalDateTime.of(2023, 5, 1, 9, 0), LocalDateTime.of(2023, 5, 1, 10, 30),
                Category.HEALTH, Optional.of("仁愛路診所"), Optional.of("帶健保卡")));
        ScheduledEvent plain = at("開會", LocalDateTime.of(2023, 5, 2, 14, 0));
        ScheduledEvent recent = at("昨天", LocalDateTime.of(2026, 10, 18, 9, 0));
        events.save(ALICE, List.of(detailed, plain, recent));
        archiving(archive).archiveEndedBefore(CUTOFF);
        assertEquals(List.of(recent), online(ALICE));
        long rowsBefore = onlineRows();

        assertEquals(List.of(detailed), viewing.listArchivedEvents(ALICE, LocalDate.of(2023, 5, 1), LocalDate.of(2023, 5, 1)));
        assertEquals(List.of(detailed, plain),
                viewing.listArchivedEvents(ALICE, LocalDate.of(2023, 5, 1), LocalDate.of(2023, 5, 31)));

        // 查看不放回資料庫：筆數不變，那兩筆也沒有回到月曆上
        assertEquals(rowsBefore, onlineRows());
        assertEquals(List.of(recent), online(ALICE));
    }

    @Test
    @DisplayName("跨月：範圍內開始、下個月才結束的行程查得到（它在下個月的包裡）；從範圍前開始的也查得到")
    void crossMonthEventIsFound() {
        ScheduledEvent trip = between("出差", LocalDateTime.of(2023, 5, 30, 9, 0), LocalDateTime.of(2023, 6, 2, 18, 0));
        events.save(ALICE, List.of(trip));
        archiving(archive).archiveEndedBefore(CUTOFF);
        // 先確認它真的放在六月的包，不在五月：這一題才是在驗「往後多讀一個月」
        assertTrue(archive.load(ALICE, MAY_2023, MAY_2023).isEmpty());

        assertEquals(List.of(trip), viewing.listArchivedEvents(ALICE, LocalDate.of(2023, 5, 30), LocalDate.of(2023, 5, 31)));
        assertEquals(List.of(trip), viewing.listArchivedEvents(ALICE, LocalDate.of(2023, 6, 1), LocalDate.of(2023, 6, 1)));
    }

    @Test
    @DisplayName("反例：範圍外的不出現；剛好在範圍開始那一刻結束的、剛好在範圍結束隔天 00:00 開始的都不算重疊")
    void outsideAndTouchingEventsAreLeftOut() {
        ScheduledEvent inside = at("範圍裡", LocalDateTime.of(2023, 5, 10, 9, 0));
        ScheduledEvent before = at("前一天", LocalDateTime.of(2023, 5, 9, 9, 0));
        ScheduledEvent after = at("後一天", LocalDateTime.of(2023, 5, 12, 9, 0));
        ScheduledEvent endsAtStart = between("前一晚到半夜", LocalDateTime.of(2023, 5, 9, 23, 0), LocalDateTime.of(2023, 5, 10, 0, 0));
        ScheduledEvent startsAtEnd = at("隔天半夜", LocalDateTime.of(2023, 5, 12, 0, 0));
        events.save(ALICE, List.of(inside, before, after, endsAtStart, startsAtEnd));
        archiving(archive).archiveEndedBefore(CUTOFF);

        assertEquals(List.of(inside), viewing.listArchivedEvents(ALICE, LocalDate.of(2023, 5, 10), LocalDate.of(2023, 5, 11)));
    }

    @Test
    @DisplayName("反例：兩個人封存了同一段期間的行程，各自只查得到自己的")
    void viewOnlyReturnsOwnersEvents() {
        ScheduledEvent alices = at("Alice 的", LocalDateTime.of(2023, 5, 1, 9, 0));
        ScheduledEvent bobs = at("Bob 的", LocalDateTime.of(2023, 5, 1, 10, 0));
        events.save(ALICE, List.of(alices));
        events.save(BOB, List.of(bobs));
        archiving(archive).archiveEndedBefore(CUTOFF);
        LocalDate may1 = LocalDate.of(2023, 5, 1);

        assertEquals(List.of(alices), viewing.listArchivedEvents(ALICE, may1, may1));
        assertEquals(List.of(bobs), viewing.listArchivedEvents(BOB, may1, may1));
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
        assertEquals(many, archive.load(ALICE, YearMonth.of(2023, 1), YearMonth.of(2023, 7)));
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
}
