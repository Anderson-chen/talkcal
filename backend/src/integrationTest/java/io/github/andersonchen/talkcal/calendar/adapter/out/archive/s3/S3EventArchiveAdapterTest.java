package io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 行程的歸檔放進真的 S3 相容儲存（RustFS）、讀回來（#54）。
 * 驗的是：讀回來跟存進去一樣、格式不靠這個 app 也讀得懂、不同人的不混、存不進去一定是例外（不會「看起來成功」）。
 */
@Testcontainers
@DisplayName("S3EventArchiveAdapter（真的 S3 相容儲存）")
class S3EventArchiveAdapterTest {

    @Container
    static final RustFsContainer RUSTFS = new RustFsContainer();

    private static final OwnerId ALICE = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000a");
    private static final OwnerId BOB = OwnerId.of("3f1c8a2e-0000-4000-8000-00000000000b");
    private static final YearMonth MARCH = YearMonth.of(2024, 3);

    static ArchiveBucket bucket;
    static S3EventArchiveAdapter archive;

    @BeforeAll
    static void createBucket() {
        RUSTFS.createBucket("events");
        bucket = RUSTFS.bucket("events");
        archive = new S3EventArchiveAdapter(bucket, Clock.systemUTC());
    }

    @BeforeEach
    void clean() {
        archive.deleteAll(ALICE);
        archive.deleteAll(BOB);
    }

    private static ScheduledEvent event(String title, LocalDateTime start) {
        return new ScheduledEvent(EventId.newId(), new CalendarEvent(title, start, start.plusHours(1)));
    }

    @Test
    @DisplayName("存進去再讀回來，每個欄位都一樣（分類、地點、備註、沒有地點的也是）")
    void roundTrip() {
        ScheduledEvent full = new ScheduledEvent(EventId.newId(), new CalendarEvent("看牙醫",
                LocalDateTime.of(2024, 3, 5, 9, 0), LocalDateTime.of(2024, 3, 5, 10, 30),
                Category.HEALTH, Optional.of("仁愛路診所"), Optional.of("帶健保卡\n第二行")));
        ScheduledEvent bare = event("開會", LocalDateTime.of(2024, 3, 20, 14, 0));

        archive.store(ALICE, MARCH, List.of(full, bare));

        assertEquals(List.of(full, bare), archive.load(ALICE, new MonthRange(MARCH, MARCH)));
    }

    @Test
    @DisplayName("格式不靠這個 app 也讀得懂：key 依人、依月份分，內容是 gzip 的 JSON Lines，一筆一行")
    void storedAsGzippedJsonLines() throws IOException {
        archive.store(ALICE, MARCH, List.of(event("開會", LocalDateTime.of(2024, 3, 20, 14, 0)),
                event("晚餐", LocalDateTime.of(2024, 3, 21, 19, 0))));

        List<String> keys = bucket.list(ArchiveBucket.ownerPrefix(S3EventArchiveAdapter.ROOT, ALICE));
        assertEquals(1, keys.size());
        assertTrue(keys.getFirst().startsWith("calendar-event/owner=" + ALICE + "/month=2024-03/"), keys.getFirst());
        assertTrue(keys.getFirst().endsWith(".jsonl.gz"), keys.getFirst());

        String text;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bucket.get(keys.getFirst())))) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> lines = text.lines().toList();
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().contains("\"title\":\"開會\""), lines.getFirst());
        assertTrue(lines.getFirst().contains("\"start\":\"2024-03-20T14:00:00\""), lines.getFirst());
        assertTrue(lines.getFirst().contains("\"v\":1"), lines.getFirst());
    }

    @Test
    @DisplayName("不同人的不混：只讀得到自己的；刪掉一個人的全部歸檔，另一個人的還在")
    void ownersAreSeparate() {
        ScheduledEvent alices = event("Alice 的", LocalDateTime.of(2024, 3, 1, 9, 0));
        ScheduledEvent bobs = event("Bob 的", LocalDateTime.of(2024, 3, 1, 9, 0));
        archive.store(ALICE, MARCH, List.of(alices));
        archive.store(BOB, MARCH, List.of(bobs));

        assertEquals(List.of(alices), archive.load(ALICE, new MonthRange(MARCH, MARCH)));
        assertEquals(List.of(bobs), archive.load(BOB, new MonthRange(MARCH, MARCH)));

        assertEquals(1, archive.deleteAll(ALICE));
        assertTrue(archive.load(ALICE, new MonthRange(MARCH, MARCH)).isEmpty());
        assertEquals(List.of(bobs), archive.load(BOB, new MonthRange(MARCH, MARCH)));
    }

    @Test
    @DisplayName("只讀要的那幾個月：範圍外的月份不會被讀進來")
    void loadsOnlyRequestedMonths() {
        ScheduledEvent feb = event("二月", LocalDateTime.of(2024, 2, 1, 9, 0));
        ScheduledEvent mar = event("三月", LocalDateTime.of(2024, 3, 1, 9, 0));
        ScheduledEvent apr = event("四月", LocalDateTime.of(2024, 4, 1, 9, 0));
        archive.store(ALICE, YearMonth.of(2024, 2), List.of(feb));
        archive.store(ALICE, MARCH, List.of(mar));
        archive.store(ALICE, YearMonth.of(2024, 4), List.of(apr));

        assertEquals(List.of(mar), archive.load(ALICE, new MonthRange(MARCH, MARCH)));
        assertEquals(List.of(feb, mar), archive.load(ALICE, new MonthRange(YearMonth.of(2024, 2), MARCH)));
    }

    @Test
    @DisplayName("同一筆存了兩次（重跑、還原後又歸檔）：讀回來只有一筆，而且是後存的那份")
    void sameEventStoredTwiceComesBackOnce() {
        ScheduledEvent first = event("初版", LocalDateTime.of(2024, 3, 1, 9, 0));
        ScheduledEvent edited = new ScheduledEvent(first.id(), first.event().withDetails("會議室 B", null));
        new S3EventArchiveAdapter(bucket, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
                .store(ALICE, MARCH, List.of(first));
        new S3EventArchiveAdapter(bucket, Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC))
                .store(ALICE, MARCH, List.of(edited));

        assertEquals(List.of(edited), archive.load(ALICE, new MonthRange(MARCH, MARCH)));
    }

    @Test
    @DisplayName("反例：存不進去（bucket 不存在、儲存連不上）一定丟例外，不會正常回傳 —— 呼叫端才不會去刪資料庫")
    void storeFailureIsAnException() {
        S3EventArchiveAdapter noBucket = new S3EventArchiveAdapter(RUSTFS.bucket("no-such-bucket"), Clock.systemUTC());
        S3EventArchiveAdapter unreachable = new S3EventArchiveAdapter(ArchiveBucket.connect("http://127.0.0.1:1",
                RustFsContainer.REGION, "events", RustFsContainer.ACCESS_KEY, RustFsContainer.SECRET_KEY), Clock.systemUTC());
        List<ScheduledEvent> events = List.of(event("開會", LocalDateTime.of(2024, 3, 1, 9, 0)));

        assertThrows(IllegalStateException.class, () -> noBucket.store(ALICE, MARCH, events));
        assertThrows(IllegalStateException.class, () -> unreachable.store(ALICE, MARCH, events));
    }

    @Test
    @DisplayName("歸檔壞掉（不是 gzip、或內容不合行程的規則）：讀的時候丟例外，不會把壞資料放回行事曆")
    void corruptArchiveIsRejected() throws IOException {
        String prefix = ArchiveBucket.ownerPrefix(S3EventArchiveAdapter.ROOT, ALICE) + "month=2024-03/";
        bucket.put(prefix + "broken.jsonl.gz", "not gzip".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> archive.load(ALICE, new MonthRange(MARCH, MARCH)));

        bucket.deleteAll(prefix);
        String endBeforeStart = "{\"v\":1,\"id\":\"" + UUID.randomUUID() + "\",\"title\":\"倒著走\","
                + "\"start\":\"2024-03-01T10:00:00\",\"end\":\"2024-03-01T09:00:00\",\"category\":\"WORK\"}";
        bucket.put(prefix + "invalid.jsonl.gz", gzip(endBeforeStart));
        assertThrows(IllegalStateException.class, () -> archive.load(ALICE, new MonthRange(MARCH, MARCH)));
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
