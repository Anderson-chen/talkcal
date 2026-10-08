package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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
    }

    private static int count() {
        return jdbc.sql("SELECT count(*) FROM calendar_event").query(Integer.class).single();
    }

    private static ScheduledEvent saved(String title, LocalDateTime start, LocalDateTime end) {
        ScheduledEvent event = ScheduledEvent.schedule(new CalendarEvent(title, start, end));
        adapter.save(List.of(event));
        return event;
    }

    private static List<String> titlesIn(DateRange range) {
        return adapter.load(range).stream().map(e -> e.event().title()).toList();
    }

    @Nested
    @DisplayName("存檔")
    class Save {

        @Test
        @DisplayName("存了讀得回來：身分、標題、起訖時間都一樣")
        void roundTrip() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);

            adapter.save(List.of(dinner));

            assertEquals(List.of(dinner), adapter.load(OCT_06));
        }

        @Test
        @DisplayName("存進去的就是牆上時間本身：用 SQL 轉成字串看，15:00 還是 15:00")
        void storesWallClockTime() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
            adapter.save(List.of(dinner));

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
            adapter.save(List.of(existing));

            ScheduledEvent fresh = ScheduledEvent.schedule(OVERNIGHT);
            ScheduledEvent duplicate = new ScheduledEvent(existing.id(), OVERNIGHT);

            assertThrows(IllegalStateException.class, () -> adapter.save(List.of(fresh, duplicate)));
            assertEquals(1, count());
        }

        @Test
        @DisplayName("資料庫的 CHECK 也擋得住倒著走的行程（萬一有人繞過 domain 直接寫 SQL）")
        void databaseCheckRejectsEndBeforeStart() {
            assertThrows(Exception.class, () -> jdbc.sql("""
                            INSERT INTO calendar_event (id, title, start_at, end_at)
                            VALUES (:id, '壞資料', '2026-10-06 15:00', '2026-10-06 14:00')
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

            adapter.save(List.of(detailed, bare));

            assertEquals(List.of(bare, detailed), adapter.load(OCT_06));
            Integer nulls = jdbc.sql("SELECT count(*) FROM calendar_event WHERE location IS NULL AND note IS NULL AND id = :id")
                    .param("id", bare.id().value())
                    .query(Integer.class).single();
            assertEquals(1, nulls);
        }

        @Test
        @DisplayName("V5 之前存的資料：分類補成 PERSONAL（跟 Category.DEFAULT 一致）")
        void columnDefaultMatchesDomainDefault() {
            jdbc.sql("""
                            INSERT INTO calendar_event (id, title, start_at, end_at)
                            VALUES (:id, '舊資料', '2026-10-06 15:00', '2026-10-06 16:00')
                            """)
                    .param("id", UUID.randomUUID())
                    .update();

            assertEquals(Category.DEFAULT, adapter.load(OCT_06).getFirst().event().category());
        }

        @Test
        @DisplayName("資料庫的 CHECK：不認得的分類、空白的地點或備註都擋下")
        void databaseChecks() {
            for (String sql : List.of(
                    "INSERT INTO calendar_event (id, title, start_at, end_at, category) VALUES (:id, '事', '2026-10-06 15:00', '2026-10-06 16:00', 'HOLIDAY')",
                    "INSERT INTO calendar_event (id, title, start_at, end_at, location) VALUES (:id, '事', '2026-10-06 15:00', '2026-10-06 16:00', '  ')",
                    "INSERT INTO calendar_event (id, title, start_at, end_at, note) VALUES (:id, '事', '2026-10-06 15:00', '2026-10-06 16:00', '')")) {
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
            adapter.save(List.of(dinner, overnight));

            assertTrue(adapter.delete(dinner.id()));

            assertEquals(List.of(overnight), adapter.load(OCT_06));
        }

        @Test
        @DisplayName("沒有這筆（或刪第二次）回 false，不丟例外 —— 算不算錯是 use case 的事")
        void missingIsFalse() {
            ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
            adapter.save(List.of(dinner));
            adapter.delete(dinner.id());

            assertFalse(adapter.delete(dinner.id()));
            assertFalse(adapter.delete(EventId.newId()));
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
                                INSERT INTO calendar_event (id, title, start_at, end_at)
                                VALUES (:id, E'\n', '2026-10-06 15:00', '2026-10-06 16:00')
                                """)
                        .param("id", UUID.randomUUID())
                        .update();

                assertThrows(IllegalStateException.class, () -> adapter.load(OCT_06));
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
                                INSERT INTO calendar_event (id, title, start_at, end_at)
                                VALUES (:id, :title, '2026-10-06 15:00', '2026-10-06 16:00')
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
                            INSERT INTO calendar_event (id, title, start_at, end_at)
                            VALUES (:id, ' 吃飯 ', '2026-10-06 15:00', '2026-10-06 16:00')
                            """)
                    .param("id", UUID.randomUUID())
                    .update();
            assertEquals(1, count());
        }
    }

    @Test
    @DisplayName("讀取的 SQL 用得到 V3 的 GiST 索引")
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
                            WHERE tsrange(start_at, end_at) && tsrange(:start, :end)
                            ORDER BY start_at, end_at, id
                            """)
                    .param("start", OCT_06.startTime())
                    .param("end", OCT_06.endTime())
                    .query(String.class)
                    .list());
        });

        assertTrue(plan.contains("calendar_event_period"), () -> "沒有用到索引，計畫是：\n" + plan);
    }

    @Test
    @DisplayName("id 欄位存的就是 EventId 的 UUID")
    void storesIdAsUuid() {
        ScheduledEvent dinner = ScheduledEvent.schedule(DINNER);
        adapter.save(List.of(dinner));

        UUID stored = jdbc.sql("SELECT id FROM calendar_event").query(UUID.class).single();
        assertEquals(dinner.id(), new EventId(stored));
    }
}
