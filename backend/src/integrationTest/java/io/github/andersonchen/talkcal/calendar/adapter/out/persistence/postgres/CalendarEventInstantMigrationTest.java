package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.andersonchen.talkcal.Migrations;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V12～V14（#18）套在「已經有資料」的資料庫上：先停在 V11、用那時候的樣子塞幾筆，再升到最新。
 *
 * 跟 JdbcCalendarEventAdapterTest 分開一個容器：那邊每支測試都從空表開始，測不到「migration 跑之前就在的資料」，
 * 而線上真正會遇到的正是這種。
 */
@Testcontainers
@DisplayName("V12～V14：起訖時間改存時間點（既有資料）")
class CalendarEventInstantMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static JdbcClient jdbc;

    private static final UUID OWNER = UUID.fromString("3f1c8a2e-0000-4000-8000-000000000001");
    private static final UUID AFTERNOON = UUID.fromString("00000000-0000-4000-8000-000000000001");
    // 台北的凌晨，換成 UTC 是前一天：換算方向弄反的話，日期會錯
    private static final UUID EARLY_MORNING = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @BeforeAll
    static void migrateWithExistingRows() {
        Migrations.flyway(POSTGRES).target("11").load().migrate();
        jdbc = JdbcClient.create(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.sql("""
                        INSERT INTO calendar_event (id, owner_id, title, start_at, end_at) VALUES
                            (:afternoon, :owner, '下午的會', '2026-10-06 15:00', '2026-10-06 16:00'),
                            (:early, :owner, '早班機', '2026-10-07 05:30', '2026-10-07 07:00')
                        """)
                .param("afternoon", AFTERNOON)
                .param("early", EARLY_MORNING)
                .param("owner", OWNER)
                .update();

        Migrations.migrate(POSTGRES);
    }

    @Test
    @DisplayName("既有的行程補上了時間點：就是台北的那個牆上時間")
    void backfillsExistingRows() {
        List<String> stored = jdbc.sql("""
                        SELECT to_char(starts_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') || ' ~ '
                               || to_char(ends_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI')
                        FROM calendar_event ORDER BY start_at
                        """)
                .query(String.class).list();

        // 台北是 UTC+8
        assertEquals(List.of("2026-10-06 07:00 ~ 2026-10-06 08:00", "2026-10-06 21:30 ~ 2026-10-06 23:00"), stored);
    }

    @Test
    @DisplayName("補完之後新欄位是 NOT NULL：之後誰都塞不進沒有時間點的列")
    void newColumnsAreNotNull() {
        List<String> nullable = jdbc.sql("""
                        SELECT column_name || '=' || is_nullable FROM information_schema.columns
                        WHERE table_name = 'calendar_event' AND column_name IN ('starts_at', 'ends_at')
                        ORDER BY column_name
                        """)
                .query(String.class).list();
        // 看欄位定義而不是試著塞 NULL：舊欄位本來就 NOT NULL，trigger 又會從舊欄位補值，
        // 用 INSERT 試的話，擋下它的可能是舊欄位，這個測試就變成因為別的原因綠燈
        assertEquals(List.of("ends_at=NO", "starts_at=NO"), nullable);
    }

    @Test
    @DisplayName("V14 的索引建好了，而且是可用的（CONCURRENTLY 失敗會留下 INVALID 的半成品）")
    void instantIndexIsValid() {
        Boolean valid = jdbc.sql("""
                        SELECT indisvalid FROM pg_index WHERE indexrelid = 'calendar_event_owner_instant'::regclass
                        """)
                .query(Boolean.class).single();
        assertEquals(Boolean.TRUE, valid);
    }
}
