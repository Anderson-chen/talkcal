package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JdbcActiveOwnersAdapter 對真的 PostgreSQL（做法同 JdbcCalendarEventAdapterTest）。
 *
 * 重點是「一個人只算一次」：同一個人在兩張表、同一張表好幾筆都有紀錄，UNION 要把他收成一個 —— 只有真的 SQL 驗得準。
 * 資料直接用 SQL 寫進去，created_at 自己給：要造出「八天前」的紀錄，走 adapter 存檔做不到。
 */
@Testcontainers
@DisplayName("JdbcActiveOwnersAdapter（真的 PostgreSQL）")
class JdbcActiveOwnersAdapterTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static JdbcClient jdbc;
    static JdbcActiveOwnersAdapter adapter;

    private static final Instant NOW = Instant.parse("2026-10-17T04:00:00Z");
    private static final Instant ONE_DAY_AGO = NOW.minusSeconds(24 * 3600);
    private static final UUID AMY = UUID.fromString("3f1c8a2e-0000-4000-8000-000000000001");
    private static final UUID BOB = UUID.fromString("3f1c8a2e-0000-4000-8000-000000000002");
    private static final UUID CAT = UUID.fromString("3f1c8a2e-0000-4000-8000-000000000003");

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        adapter = new JdbcActiveOwnersAdapter(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM calendar_event").update();
        jdbc.sql("DELETE FROM calendar_assistant_message").update();
    }

    private static void savedEvent(UUID owner, Instant at) {
        jdbc.sql("""
                        INSERT INTO calendar_event (id, owner_id, title, start_at, end_at, created_at)
                        VALUES (:id, :owner, '吃飯', '2026-10-20 19:00', '2026-10-20 20:00', :at)
                        """)
                .param("id", UUID.randomUUID()).param("owner", owner).param("at", Timestamp.from(at)).update();
    }

    private static int position;

    private static void talked(UUID owner, Instant at) {
        jdbc.sql("""
                        INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content, created_at)
                        VALUES (:owner, :conversation, :position, 'USER', '嗨', :at)
                        """)
                .param("owner", owner).param("conversation", UUID.randomUUID()).param("position", position++)
                .param("at", Timestamp.from(at)).update();
    }

    @Test
    @DisplayName("存過行程或跟助理說過話的都算；同一個人兩邊都有、好幾筆，只算一次")
    void countsEachOwnerOnce() {
        savedEvent(AMY, NOW.minusSeconds(60));
        savedEvent(AMY, NOW.minusSeconds(120));
        talked(AMY, NOW.minusSeconds(30));
        talked(BOB, NOW.minusSeconds(3600));

        assertEquals(List.of(2), adapter.countActiveOwners(List.of(ONE_DAY_AGO)));
    }

    @Test
    @DisplayName("幾個期間一句 SQL 一起數，各自只算自己期間內的人，照傳進來的順序回")
    void countsSeveralWindowsAtOnce() {
        talked(AMY, NOW.minusSeconds(60));
        savedEvent(BOB, NOW.minusSeconds(3 * 24 * 3600));
        talked(CAT, NOW.minusSeconds(20 * 24 * 3600));

        assertEquals(List.of(3, 1, 2), adapter.countActiveOwners(List.of(
                NOW.minusSeconds(30L * 24 * 3600), ONE_DAY_AGO, NOW.minusSeconds(7 * 24 * 3600))));
    }

    @Test
    @DisplayName("反例：期間之前的紀錄不算（八天前存過行程的人不是今天的活躍使用者）")
    void ignoresActivityBeforeSince() {
        savedEvent(CAT, NOW.minusSeconds(8 * 24 * 3600));
        talked(CAT, NOW.minusSeconds(8 * 24 * 3600));

        assertEquals(List.of(0, 1), adapter.countActiveOwners(List.of(ONE_DAY_AGO, NOW.minusSeconds(30L * 24 * 3600))));
    }

    @Test
    @DisplayName("邊界只差一小時也分得出來：時區換算錯了（例如差 8 小時）這裡會紅")
    void boundaryIsExactInstant() {
        talked(AMY, ONE_DAY_AGO.plusSeconds(3600));
        talked(BOB, ONE_DAY_AGO.minusSeconds(3600));

        assertEquals(List.of(1), adapter.countActiveOwners(List.of(ONE_DAY_AGO)));
    }

    @Test
    @DisplayName("沒有任何紀錄：0")
    void emptyIsZero() {
        assertEquals(List.of(0), adapter.countActiveOwners(List.of(ONE_DAY_AGO)));
    }

    @Test
    @DisplayName("資料庫連不上：IllegalStateException")
    void databaseDownIsIllegalState() {
        JdbcActiveOwnersAdapter broken = new JdbcActiveOwnersAdapter(
                JdbcClient.create(new DriverManagerDataSource("jdbc:postgresql://localhost:1/none", "x", "x")));

        assertThrows(IllegalStateException.class, () -> broken.countActiveOwners(List.of(ONE_DAY_AGO)));
    }
}
