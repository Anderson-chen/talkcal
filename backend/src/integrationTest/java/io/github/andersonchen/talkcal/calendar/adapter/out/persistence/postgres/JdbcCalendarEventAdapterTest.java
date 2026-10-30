package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.service.AddEventsService;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddedEvents;
import io.github.andersonchen.talkcal.calendar.application.port.in.IdempotencyKeyReusedException;
import io.github.andersonchen.talkcal.calendar.application.port.out.ProposalStorePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.AlreadySaved;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.KeyUsedForOtherContent;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.Saved;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JdbcCalendarEventAdapter 對真的 PostgreSQL（做法同 JdbcConversationAdapterTest）。
 *
 * 讀取這半邊的重點是邊界：「有重疊」的定義在 port 上寫成一句話，
 * 但它其實是 SQL 的 && 加上 tsrange 預設的 [) 邊界一起決定的 —— 只有真的 PostgreSQL 驗得準。
 */
@Testcontainers
@DisplayName("JdbcCalendarEventAdapter（真的 PostgreSQL）")
class JdbcCalendarEventAdapterTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static JdbcCalendarEventAdapter adapter;
    static JdbcClient jdbc;
    static TransactionTemplate transaction;

    // 直接寫 SQL 的測試也帶著擁有者：不然每一筆都會先撞上 V11 的 NOT NULL，
    // 「CHECK 擋下了」的測試就變成因為別的原因被擋、照樣綠燈
    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final OwnerId OTHER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000002");

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0));
    private static final CalendarEvent OVERNIGHT =
            new CalendarEvent("唱歌", LocalDateTime.of(2026, 10, 5, 22, 0), LocalDateTime.of(2026, 10, 6, 1, 0));

    // 10/06 這一天
    private static final DateRange OCT_06 = new DateRange(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 7));

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        adapter = new JdbcCalendarEventAdapter(jdbc, transaction);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM calendar_add_request").update();
    }

    private static final ProposalStorePort NO_PROPOSALS = new ProposalStorePort() {
        @Override
        public void saveProposals(OwnerId owner, List<ProposalId> proposals) {
        }

        @Override
        public int decide(OwnerId owner, Set<ProposalId> proposals, ProposalOutcome outcome) {
            return 0;
        }
    };

    private static final RecordUsagePort NO_USAGE = new RecordUsagePort() {
        @Override
        public void proposalsShown(int count) {
        }

        @Override
        public void proposalsDismissed(int count) {
        }

        @Override
        public void eventsAdded(EventOrigin origin, int count) {
        }
    };

    private static int count() {
        return jdbc.sql("SELECT count(*) FROM calendar_event").query(Integer.class).single();
    }

    private static ScheduledEvent saved(String title, LocalDateTime start, LocalDateTime end) {
        ScheduledEvent event = ScheduledEvent.schedule(new CalendarEvent(title, start, end));
        adapter.save(OWNER, List.of(event));
        return event;
    }

    private static List<String> titlesIn(DateRange range) {
        return adapter.load(OWNER, range).stream().map(e -> e.event().title()).toList();
    }

    @Nested
    @DisplayName("存檔")
    class Save {

        @Test
        @DisplayName("存了讀得回來：身分、標題、起訖時間都一樣")
        void roundTrip() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);

            adapter.save(OWNER, List.of(dinner));

            assertEquals(List.of(dinner), adapter.load(OWNER, OCT_06));
        }

        @Test
        @DisplayName("存進去的就是牆上時間本身：用 SQL 轉成字串看，15:00 還是 15:00")
        void storesWallClockTime() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
            adapter.save(OWNER, List.of(dinner));

            // 直接用字串讀，不經過 JDBC 的型別轉換：看的是資料庫裡實際存的值
            String stored = jdbc.sql("SELECT to_char(start_at, 'YYYY-MM-DD HH24:MI') FROM calendar_event WHERE id = :id")
                    .param("id", dinner.id().value())
                    .query(String.class).single();
            assertEquals("2026-10-06 15:00", stored);
        }

        @Test
        @DisplayName("全部存或全部不存：第二筆 ID 重複，第一筆也不會留下來")
        void allOrNothing() {
            ScheduledEvent existing = ScheduledEvent.schedule(DINNER);
            adapter.save(OWNER, List.of(existing));

            ScheduledEvent fresh = ScheduledEvent.schedule(OVERNIGHT);
            ScheduledEvent duplicate = new ScheduledEvent(existing.id(), OVERNIGHT);

            assertThrows(IllegalStateException.class, () -> adapter.save(OWNER, List.of(fresh, duplicate)));
            assertEquals(1, count());
        }

        @Test
        @DisplayName("資料庫的 CHECK 也擋得住倒著走的行程（萬一有人繞過 domain 直接寫 SQL）")
        void databaseCheckRejectsEndBeforeStart() {
            assertThrows(Exception.class, () -> jdbc.sql("""
                            INSERT INTO calendar_event (owner_id, id, title, start_at, end_at)
                            VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, '壞資料', '2026-10-06 15:00', '2026-10-06 14:00')
                            """)
                    .param("id", UUID.randomUUID())
                    .update());
            assertEquals(0, count());
        }
    }

    @Nested
    @DisplayName("分類、地點、備註（V5）")
    class Details {

        @Test
        @DisplayName("存了讀得回來；沒有地點、備註的存成 NULL")
        void roundTrip() {
            ScheduledEvent detailed = ScheduledEvent.schedule(DINNER.withCategory(Category.SOCIAL).withDetails("拉麵店", "記得訂位"));
            ScheduledEvent bare = ScheduledEvent.schedule(OVERNIGHT);

            adapter.save(OWNER, List.of(detailed, bare));

            assertEquals(List.of(bare, detailed), adapter.load(OWNER, OCT_06));
            Integer nulls = jdbc.sql("SELECT count(*) FROM calendar_event WHERE location IS NULL AND note IS NULL AND id = :id")
                    .param("id", bare.id().value())
                    .query(Integer.class).single();
            assertEquals(1, nulls);
        }

        @Test
        @DisplayName("V5 之前存的資料：分類補成 PERSONAL（跟 Category.DEFAULT 一致）")
        void columnDefaultMatchesDomainDefault() {
            jdbc.sql("""
                            INSERT INTO calendar_event (owner_id, id, title, start_at, end_at)
                            VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, '舊資料', '2026-10-06 15:00', '2026-10-06 16:00')
                            """)
                    .param("id", UUID.randomUUID())
                    .update();

            assertEquals(Category.DEFAULT, adapter.load(OWNER, OCT_06).getFirst().event().category());
        }

        @Test
        @DisplayName("資料庫的 CHECK：不認得的分類、空白的地點或備註都擋下")
        void databaseChecks() {
            for (String sql : List.of(
                    "INSERT INTO calendar_event (owner_id, id, title, start_at, end_at, category) VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, '事', '2026-10-06 15:00', '2026-10-06 16:00', 'HOLIDAY')",
                    "INSERT INTO calendar_event (owner_id, id, title, start_at, end_at, location) VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, '事', '2026-10-06 15:00', '2026-10-06 16:00', '  ')",
                    "INSERT INTO calendar_event (owner_id, id, title, start_at, end_at, note) VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, '事', '2026-10-06 15:00', '2026-10-06 16:00', '')")) {
                assertThrows(Exception.class, () -> jdbc.sql(sql).param("id", UUID.randomUUID()).update(), sql);
            }
            assertEquals(0, count());
        }
    }

    @Nested
    @DisplayName("刪除")
    class Delete {

        @Test
        @DisplayName("刪到了回 true，只刪那一筆")
        void deletesOnlyThatOne() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
            ScheduledEvent overnight = ScheduledEvent.schedule(OVERNIGHT);
            adapter.save(OWNER, List.of(dinner, overnight));

            assertTrue(adapter.delete(OWNER, dinner.id()));

            assertEquals(List.of(overnight), adapter.load(OWNER, OCT_06));
        }

        @Test
        @DisplayName("沒有這筆（或刪第二次）回 false，不丟例外 —— 算不算錯是 use case 的事")
        void missingIsFalse() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
            adapter.save(OWNER, List.of(dinner));
            adapter.delete(OWNER, dinner.id());

            assertFalse(adapter.delete(OWNER, dinner.id()));
            assertFalse(adapter.delete(OWNER, EventId.newId()));
        }
    }

    @Nested
    @DisplayName("讀出一段期間：有重疊就算")
    class Load {

        @Test
        @DisplayName("完全在期間裡的、從前一天跨夜進來的，都讀得到")
        void insideAndOvernightFromPreviousDay() {
            saved("唱歌", LocalDateTime.of(2026, 10, 5, 22, 0), LocalDateTime.of(2026, 10, 6, 1, 0));
            saved("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0), LocalDateTime.of(2026, 10, 6, 16, 0));

            assertEquals(List.of("唱歌", "跟小明吃飯"), titlesIn(OCT_06));
        }

        @Test
        @DisplayName("跨夜到隔天的：今天、明天兩頁都看得到")
        void overnightIntoNextDayAppearsOnBothDays() {
            saved("夜唱", LocalDateTime.of(2026, 10, 6, 22, 0), LocalDateTime.of(2026, 10, 7, 2, 0));
            DateRange oct07 = new DateRange(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8));

            assertEquals(List.of("夜唱"), titlesIn(OCT_06));
            assertEquals(List.of("夜唱"), titlesIn(oct07));
        }

        @Test
        @DisplayName("比整個期間還長的行程（例如出差三天）也算重疊")
        void spansTheWholeRange() {
            saved("出差", LocalDateTime.of(2026, 10, 5, 8, 0), LocalDateTime.of(2026, 10, 8, 18, 0));

            assertEquals(List.of("出差"), titlesIn(OCT_06));
        }

        @Test
        @DisplayName("邊界：剛好在期間開始那一刻結束的、剛好在期間結束那一刻開始的，都不算")
        void touchingTheBoundaryIsNotOverlapping() {
            saved("前一天的", LocalDateTime.of(2026, 10, 5, 23, 0), LocalDateTime.of(2026, 10, 6, 0, 0));
            saved("後一天的", LocalDateTime.of(2026, 10, 7, 0, 0), LocalDateTime.of(2026, 10, 7, 1, 0));

            assertEquals(List.of(), titlesIn(OCT_06));
        }

        @Test
        @DisplayName("依開始時間排序，開始時間相同就依結束時間")
        void orderedByStartThenEnd() {
            saved("晚的", LocalDateTime.of(2026, 10, 6, 18, 0), LocalDateTime.of(2026, 10, 6, 19, 0));
            saved("長的", LocalDateTime.of(2026, 10, 6, 9, 0), LocalDateTime.of(2026, 10, 6, 12, 0));
            saved("短的", LocalDateTime.of(2026, 10, 6, 9, 0), LocalDateTime.of(2026, 10, 6, 10, 0));

            assertEquals(List.of("短的", "長的", "晚的"), titlesIn(OCT_06));
        }

        @Test
        @DisplayName("資料庫裡有 domain 不接受的資料：讀出來時被擋下，丟 IllegalStateException（502，不是 400）")
        void corruptRowBecomesUpstreamFailure() {
            // V4 之後，資料庫的 CHECK 跟 domain 一樣嚴，正常的 INSERT 已經塞不進空白標題了。
            // 要模擬「資料庫裡有壞資料」（例如規則變嚴之前就存在的舊資料、有人手動改過），
            // 就在一個交易裡暫時拆掉 CHECK、塞一筆、讀讀看，最後 rollback。
            // PostgreSQL 的 DDL 也在交易裡，所以 rollback 連拆掉的 CHECK 都會裝回去，不影響其他測試。
            // adapter 用的是同一個 DataSource，交易裡它拿到的就是這條連線，看得到那筆還沒 commit 的壞資料
            transaction.executeWithoutResult(status -> {
                jdbc.sql("ALTER TABLE calendar_event DROP CONSTRAINT calendar_event_title_not_blank").update();
                jdbc.sql("""
                                INSERT INTO calendar_event (owner_id, id, title, start_at, end_at)
                                VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, E'\n', '2026-10-06 15:00', '2026-10-06 16:00')
                                """)
                        .param("id", UUID.randomUUID())
                        .update();

                assertThrows(IllegalStateException.class, () -> adapter.load(OWNER, OCT_06));
                status.setRollbackOnly();
            });

            assertEquals(0, count(), "rollback 之後壞資料應該不見了");
        }
    }

    @Nested
    @DisplayName("資料庫自己的 CHECK（萬一有人繞過 domain 直接寫 SQL）")
    class DatabaseChecks {

        /**
         * V2 的 btrim 只去掉半形空格，這三種都放得進來；V4 改成 title ~ '\S' 之後跟 domain 的 isBlank 一致。
         */
        @Test
        @DisplayName("空白標題一律擋下：半形空格、換行、tab、全形空白")
        void rejectsBlankTitles() {
            for (String blank : List.of("   ", "\n", "\t", " \n ", "\u3000")) {
                assertThrows(Exception.class, () -> jdbc.sql("""
                                INSERT INTO calendar_event (owner_id, id, title, start_at, end_at)
                                VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, :title, '2026-10-06 15:00', '2026-10-06 16:00')
                                """)
                        .param("id", UUID.randomUUID())
                        .param("title", blank)
                        .update(), () -> "應該擋下標題 [" + blank.codePoints().mapToObj(Integer::toHexString).toList() + "]");
            }
            assertEquals(0, count());
        }

        @Test
        @DisplayName("前後有空白但中間有字的標題照樣收")
        void acceptsTitleWithSurroundingSpaces() {
            jdbc.sql("""
                            INSERT INTO calendar_event (owner_id, id, title, start_at, end_at)
                            VALUES ('3f1c8a2e-0000-4000-8000-000000000001', :id, ' 吃飯 ', '2026-10-06 15:00', '2026-10-06 16:00')
                            """)
                    .param("id", UUID.randomUUID())
                    .update();
            assertEquals(1, count());
        }
    }

    @Nested
    @DisplayName("#104 擁有者：只碰得到自己的")
    class Owner {

        @Test
        @DisplayName("同一段期間裡別人的行程，讀不到")
        void loadsOnlyOwnEvents() {
            ScheduledEvent mine = ScheduledEvent.schedule(DINNER);
            ScheduledEvent theirs = ScheduledEvent.schedule(OVERNIGHT);
            adapter.save(OWNER, List.of(mine));
            adapter.save(OTHER, List.of(theirs));

            assertEquals(List.of(mine), adapter.load(OWNER, OCT_06));
            assertEquals(List.of(theirs), adapter.load(OTHER, OCT_06));
        }

        @Test
        @DisplayName("IDOR 反例：拿別人行程的 id 來刪 —— 沒刪到（false），那筆還在")
        void cannotDeleteOthersEvent() {
            ScheduledEvent theirs = ScheduledEvent.schedule(DINNER);
            adapter.save(OTHER, List.of(theirs));

            assertFalse(adapter.delete(OWNER, theirs.id()));

            assertEquals(List.of(theirs), adapter.load(OTHER, OCT_06));
            assertTrue(adapter.delete(OTHER, theirs.id()), "擁有者本人刪得到");
        }

        @Test
        @DisplayName("存進去的 owner_id 就是那個擁有者")
        void storesOwner() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
            adapter.save(OWNER, List.of(dinner));

            UUID stored = jdbc.sql("SELECT owner_id FROM calendar_event WHERE id = :id").param("id", dinner.id().value())
                    .query(UUID.class).single();
            assertEquals(OWNER.value(), stored);
        }

        @Test
        @DisplayName("資料庫也不收沒有擁有者的行程（V11 的 NOT NULL）")
        void ownerIsRequired() {
            assertThrows(Exception.class, () -> jdbc.sql("""
                            INSERT INTO calendar_event (id, title, start_at, end_at)
                            VALUES (:id, '沒有主人', '2026-10-06 15:00', '2026-10-06 16:00')
                            """)
                    .param("id", UUID.randomUUID())
                    .update());
            assertEquals(0, count());
        }
    }

    @Test
    @DisplayName("讀取的 SQL 用得到 V11 的 GiST 索引（擁有者＋期間）")
    void loadQueryCanUseTheGistIndex() {
        // 表裡只有幾筆時，規劃器會選全表掃描（那對小表確實比較快），從 EXPLAIN 看不出索引能不能用。
        // 所以在交易裡用 SET LOCAL 暫時禁止全表掃描：如果 SQL 的運算式跟索引對不上，
        // 規劃器就算被禁止也只能硬掃（計畫裡會出現 Seq Scan）；對得上才會換成用索引。
        // SET LOCAL 只活到交易結束，不會影響其他測試。
        String plan = transaction.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("""
                            EXPLAIN SELECT id, title, start_at, end_at
                            FROM calendar_event
                            WHERE owner_id = :owner AND tsrange(start_at, end_at) && tsrange(:start, :end)
                            ORDER BY start_at, end_at, id
                            """)
                    .param("owner", OWNER.value())
                    .param("start", OCT_06.startTime())
                    .param("end", OCT_06.endTime())
                    .query(String.class)
                    .list());
        });

        assertTrue(plan.contains("calendar_event_owner_period"), () -> "沒有用到索引，計畫是：\n" + plan);
    }

    @Test
    @DisplayName("資料庫連不上：存、讀、刪都丟 IllegalStateException（502）—— 存檔的失敗發生在開交易那一步，也要包起來")
    void databaseDownIsUpstreamFailure() {
        DriverManagerDataSource nowhere = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none", "x", "x");
        JdbcCalendarEventAdapter broken = new JdbcCalendarEventAdapter(JdbcClient.create(nowhere),
                new TransactionTemplate(new DataSourceTransactionManager(nowhere)));

        assertThrows(IllegalStateException.class, () -> broken.save(OWNER, List.of(ScheduledEvent.schedule(DINNER))));
        assertThrows(IllegalStateException.class, () -> broken.load(OWNER, OCT_06));
        assertThrows(IllegalStateException.class, () -> broken.delete(OWNER, EventId.newId()));
    }

    /**
     * 冪等寫入（#51，表和理由見 V13）。前幾題經過 AddEventsService 打真的資料庫：測的是使用者實際遇到的「按兩次加入」，
     * 不只是 adapter 的某個方法。
     *
     * 怎麼證明這些題目真的抓得到重複：withoutKeyDuplicates 示範同樣的操作不帶鍵就是兩筆（偵測方法是對的）；
     * 把 saveOnce 搶鍵的那句拿掉（每次都當成第一次），sameKeyTwiceSavesOnce、concurrentRetriesSaveOnce 等題會紅（PR 描述有實際跑的結果）。
     */
    @Nested
    @DisplayName("#51 冪等鍵：同一個鍵只存一次")
    class Idempotency {

        // 卡片和用量指標不是這裡要測的（AddEventsServiceTest 測）：給兩個什麼都不做的
        private final AddEventsService service = new AddEventsService(adapter, adapter, NO_PROPOSALS, NO_USAGE);
        private final IdempotencyKey key = new IdempotencyKey("3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10");
        private final CalendarEvent dentist = CalendarEvent.startingAt("看牙醫", LocalDateTime.of(2026, 10, 6, 9, 0));

        @Test
        @DisplayName("同一個鍵送兩次（回應弄丟、使用者再按一次）：資料庫裡只有一筆，第二次回的是第一次那筆的 id")
        void sameKeyTwiceSavesOnce() {
            AddedEvents first = service.addEvents(OWNER, key, List.of(DINNER), List.of());
            AddedEvents retry = service.addEvents(OWNER, key, List.of(DINNER), List.of());

            assertEquals(1, count());
            assertFalse(first.replayed());
            assertTrue(retry.replayed());
            assertEquals(first.events(), retry.events());
        }

        @Test
        @DisplayName("反例：同樣的操作不帶鍵就是兩筆 —— 上一題的「只有一筆」是鍵擋下來的，不是偵測方法有問題")
        void withoutKeyDuplicates() {
            service.addEvents(OWNER, List.of(DINNER), List.of());
            service.addEvents(OWNER, List.of(DINNER), List.of());

            assertEquals(2, count());
        }

        @Test
        @DisplayName("一批好幾筆：重送回的是同樣幾筆、同樣順序")
        void batchReplaysInOrder() {
            AddedEvents first = service.addEvents(OWNER, key, List.of(DINNER, dentist), List.of());
            AddedEvents retry = service.addEvents(OWNER, key, List.of(DINNER, dentist), List.of());

            assertEquals(2, count());
            assertEquals(first.events(), retry.events());
        }

        @Test
        @DisplayName("不同的鍵、一樣的內容：正常新增，不被擋（使用者真的想加兩筆一樣的）")
        void differentKeysAreNotBlocked() {
            service.addEvents(OWNER, key, List.of(DINNER), List.of());
            AddedEvents second = service.addEvents(OWNER, new IdempotencyKey("another-key"), List.of(DINNER), List.of());

            assertEquals(2, count());
            assertFalse(second.replayed());
        }

        @Test
        @DisplayName("#104 不同使用者用同一個鍵：互不影響，各存各的，也讀不到對方那筆")
        void keysAreScopedByOwner() {
            AddedEvents mine = service.addEvents(OWNER, key, List.of(DINNER), List.of());
            AddedEvents theirs = service.addEvents(OTHER, key, List.of(DINNER), List.of());

            assertEquals(2, count());
            assertFalse(theirs.replayed());
            assertNotEquals(mine.events(), theirs.events());
            assertEquals(mine.events(), adapter.load(OWNER, OCT_06));
            assertEquals(theirs.events(), adapter.load(OTHER, OCT_06));
        }

        @Test
        @DisplayName("同一個鍵、不同內容：IdempotencyKeyReusedException（422），什麼都不存，第一次那筆不受影響")
        void sameKeyOtherContentIsRejected() {
            AddedEvents first = service.addEvents(OWNER, key, List.of(DINNER), List.of());

            assertThrows(IdempotencyKeyReusedException.class, () -> service.addEvents(OWNER, key, List.of(dentist), List.of()));
            // 內容只差一點也算不同：使用者在卡片上改了結束時間，就是另一批
            CalendarEvent longer = new CalendarEvent(DINNER.title(), DINNER.start(), DINNER.end().plusMinutes(30));
            assertInstanceOf(KeyUsedForOtherContent.class, adapter.saveOnce(OWNER, key, List.of(ScheduledEvent.schedule(longer))));
            assertEquals(first.events(), adapter.load(OWNER, OCT_06));
        }

        @Test
        @DisplayName("幾乎同時到的兩個重送（連點、兩個分頁）：唯一約束仲裁，只存一筆，兩邊拿到同一個 id")
        void concurrentRetriesSaveOnce() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // 跑 20 輪，每輪換一個鍵：兩條執行緒等同一個起跑信號，盡量讓兩個 INSERT 撞在一起
                for (int round = 0; round < 20; round++) {
                    IdempotencyKey roundKey = new IdempotencyKey("race-" + round);
                    CountDownLatch start = new CountDownLatch(1);
                    CompletableFuture<AddedEvents> one = CompletableFuture.supplyAsync(() -> awaitThenAdd(start, roundKey), pool);
                    CompletableFuture<AddedEvents> two = CompletableFuture.supplyAsync(() -> awaitThenAdd(start, roundKey), pool);
                    start.countDown();
                    AddedEvents a = one.get();
                    AddedEvents b = two.get();

                    assertEquals(round + 1, count(), "第 " + round + " 輪多存了");
                    assertEquals(a.events(), b.events());
                    assertTrue(a.replayed() ^ b.replayed(), "剛好一邊是真的存、一邊是重送");
                }
            } finally {
                pool.shutdownNow();
            }
        }

        private AddedEvents awaitThenAdd(CountDownLatch start, IdempotencyKey roundKey) {
            try {
                start.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return service.addEvents(OWNER, roundKey, List.of(DINNER), List.of());
        }

        @Test
        @DisplayName("存了之後被刪掉：重送不會把它變回來，回的是現在還在的那幾筆")
        void replayAfterDeleteDoesNotResurrect() {
            AddedEvents first = service.addEvents(OWNER, key, List.of(DINNER, dentist), List.of());
            adapter.delete(OWNER, first.events().getFirst().id());

            AlreadySaved retry = assertInstanceOf(AlreadySaved.class,
                    adapter.saveOnce(OWNER, key, List.of(ScheduledEvent.schedule(DINNER), ScheduledEvent.schedule(dentist))));

            assertEquals(1, count());
            assertEquals(List.of(first.events().get(1)), retry.events());
        }

        @Test
        @DisplayName("一批裡有一筆存不進去：整批 rollback，鍵也沒記下 —— 用同一個鍵再送，照樣存得進去")
        void failedBatchDoesNotBurnTheKey() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
            // 同一個 id 兩次：第二筆撞 calendar_event 的主鍵
            assertThrows(IllegalStateException.class, () -> adapter.saveOnce(OWNER, key, List.of(dinner, dinner)));
            assertEquals(0, count());

            assertInstanceOf(Saved.class, adapter.saveOnce(OWNER, key, List.of(dinner, ScheduledEvent.schedule(dentist))));
            assertEquals(2, count());
        }

        @Test
        @DisplayName("清掉保留期限以前的鍵：清了之後同一個鍵再送就是新的一次（V13 寫的代價）")
        void cleanup() {
            service.addEvents(OWNER, key, List.of(DINNER), List.of());

            assertEquals(0, adapter.deleteIdempotencyKeysCreatedBefore(Instant.now().minus(1, ChronoUnit.HOURS)));
            assertEquals(1, adapter.deleteIdempotencyKeysCreatedBefore(Instant.now().plus(1, ChronoUnit.MINUTES)));

            assertFalse(service.addEvents(OWNER, key, List.of(DINNER), List.of()).replayed());
            assertEquals(2, count());
        }

        @Test
        @DisplayName("內容指紋：一樣的內容算出一樣的；欄位的值換了位置（標題和地點對調）算出不一樣的")
        void fingerprintIsUnambiguous() {
            CalendarEvent a = DINNER.withDetails("拉麵店", null);
            CalendarEvent b = new CalendarEvent("拉麵店", DINNER.start(), DINNER.end()).withDetails(DINNER.title(), null);

            assertEquals(JdbcCalendarEventAdapter.fingerprint(List.of(ScheduledEvent.schedule(a))),
                    JdbcCalendarEventAdapter.fingerprint(List.of(ScheduledEvent.schedule(a))));
            assertNotEquals(JdbcCalendarEventAdapter.fingerprint(List.of(ScheduledEvent.schedule(a))),
                    JdbcCalendarEventAdapter.fingerprint(List.of(ScheduledEvent.schedule(b))));
        }
    }

    @Test
    @DisplayName("id 欄位存的就是 EventId 的 UUID")
    void storesIdAsUuid() {
        ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
        adapter.save(OWNER, List.of(dinner));

        UUID stored = jdbc.sql("SELECT id FROM calendar_event").query(UUID.class).single();
        assertEquals(dinner.id(), new EventId(stored));
    }
}
