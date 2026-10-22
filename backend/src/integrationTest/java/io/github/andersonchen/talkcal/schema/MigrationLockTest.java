package io.github.andersonchen.talkcal.schema;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * migration 跑的當下，正在處理的請求不會被卡住 —— 「零停機」裡「不停」的那一半（#18）。
 *
 * 新舊版本能不能共用 schema 是 ExpandContractDrillTest；這裡只看鎖：migration 跟同時進來的讀寫搶同一張表時會發生什麼事。
 * 「還沒結束的請求」用真的交易模擬：開了、碰過表、還沒 commit。
 *
 * 每個設計都配一個反例：拿掉那個設計（不設 lock_timeout、不用 CONCURRENTLY、Flyway 用預設的鎖），
 * 同樣的情境就真的會卡住 —— 證明那個設計不是多寫的。
 */
@Testcontainers
@DisplayName("#18 migration 跑的當下，請求不被卡住")
class MigrationLockTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    // 「沒被卡住」的上限。正常的一次寫入是幾毫秒，留很寬是給 CI 的慢機器，不是預期真的要這麼久
    private static final Duration NOT_BLOCKED = Duration.ofSeconds(3);

    private final ExecutorService background = Executors.newCachedThreadPool();
    private DrillDatabase drill;

    @BeforeEach
    void freshDatabase() throws SQLException {
        drill = DrillDatabase.create(POSTGRES);
    }

    @AfterEach
    void cleanUp() throws SQLException {
        // 先砍連線，故意卡住的 migration 執行緒才會結束
        drill.close();
        background.shutdownNow();
    }

    /** 一個讀過聯絡人、還沒結束的請求：手上拿著 drill_contact 的 ACCESS SHARE 鎖，直到 commit 或 rollback。 */
    private Connection requestStillReading() throws SQLException {
        Connection c = drill.connect();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("SELECT count(*) FROM drill_contact");
        }
        return c;
    }

    /** 一個寫到一半、還沒 commit 的請求：手上拿著 drill_contact 的 ROW EXCLUSIVE 鎖。 */
    private Connection requestStillWriting() throws SQLException {
        Connection c = drill.connect();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO drill_contact (id, phone, phone_number) VALUES (gen_random_uuid(), '寫到一半', '寫到一半')");
        }
        return c;
    }

    /** 背景送一個「讀聯絡人」的請求，回傳它從送出到拿到結果花了多久。 */
    private Future<Duration> readInBackground() {
        return background.submit(() -> {
            long start = System.nanoTime();
            try (Connection c = DriverManager.getConnection(drill.url(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 Statement s = c.createStatement()) {
                s.execute("SELECT count(*) FROM drill_contact");
            }
            return Duration.ofNanos(System.nanoTime() - start);
        });
    }

    /** R3 在線的新版程式存一筆聯絡人。 */
    private void saveContact() {
        ContactApp.V3.save(drill.jdbc(), UUID.randomUUID(), "0912");
    }

    /** 等到一句含有 sqlFragment 的 SQL 在這個資料庫裡卡在等鎖。用來確定「migration 已經在排隊了」再往下做，不靠 sleep 猜。 */
    private void awaitWaitingForLock(String sqlFragment) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).until(() -> {
            try (Connection c = DriverManager.getConnection(drill.url(), POSTGRES.getUsername(), POSTGRES.getPassword());
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT count(*) FROM pg_stat_activity
                         WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE ?
                         """)) {
                ps.setString(1, "%" + sqlFragment + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1) > 0;
                }
            }
        });
    }

    private static boolean causedByLockTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("lock timeout")) {
                return true;
            }
        }
        return false;
    }

    @Nested
    @DisplayName("ALTER TABLE 拿不到鎖：lock_timeout 讓它放棄，而不是讓後面的請求跟著排隊")
    class LockTimeout {

        @Test
        @DisplayName("有請求還沒結束：V2 約 5 秒就放棄、整支 rollback；排在它後面的讀取最多多等那幾秒；請求結束後重跑就過")
        void alterTableGivesUpInsteadOfBlockingEveryone() throws Exception {
            drill.migrateTo("1");
            Connection request = requestStillReading();

            long start = System.nanoTime();
            Future<?> migration = background.submit(() -> drill.migrateTo("2"));
            awaitWaitingForLock("ADD COLUMN phone_number");
            // ALTER TABLE 在排 ACCESS EXCLUSIVE 鎖的時候，之後進來的讀取也得排在它後面 —— 這就是停機的來源
            Future<Duration> laterRead = readInBackground();

            ExecutionException failed = assertThrows(ExecutionException.class, () -> migration.get(20, SECONDS));
            Duration migrationTook = Duration.ofNanos(System.nanoTime() - start);
            assertTrue(causedByLockTimeout(failed), () -> "應該是 lock_timeout 讓它放棄，實際是：" + failed.getCause());
            assertTrue(migrationTook.compareTo(Duration.ofSeconds(15)) < 0, () -> "放棄得太慢：" + migrationTook);

            Duration readTook = laterRead.get(20, SECONDS);
            assertTrue(readTook.compareTo(Duration.ofSeconds(10)) < 0, () -> "後面的讀取被卡太久：" + readTook);

            // 失敗是整支 rollback，不留半套：請求結束之後，同一支直接重跑就過
            request.rollback();
            assertDoesNotThrow(() -> drill.migrateTo("2"));
        }

        @Test
        @DisplayName("反例：同一句 ALTER TABLE 不設 lock_timeout，只要那個請求不結束，後面的讀取就一直卡著")
        void withoutLockTimeoutEveryoneQueues() throws Exception {
            drill.migrateTo("1");
            Connection request = requestStillReading();

            Connection migration = drill.connect();
            Future<?> alter = background.submit(() -> {
                try (Statement s = migration.createStatement()) {
                    s.execute("ALTER TABLE drill_contact ADD COLUMN phone_number TEXT");
                }
                return null;
            });
            awaitWaitingForLock("ADD COLUMN phone_number");
            Future<Duration> laterRead = readInBackground();

            // 比 V2 的 5 秒久：有 lock_timeout 的話，這時候早就放行了
            await().during(Duration.ofSeconds(7)).atMost(Duration.ofSeconds(8)).until(() -> !laterRead.isDone());

            request.rollback();
            alter.get(10, SECONDS);
            laterRead.get(10, SECONDS);
        }
    }

    @Nested
    @DisplayName("建索引：CONCURRENTLY 不擋寫入")
    class ConcurrentIndex {

        @Test
        @DisplayName("V6 在等寫到一半的請求時，程式照樣存得進聯絡人；那個請求一結束 V6 就跑完")
        void writesKeepGoingWhileIndexIsBuilt() throws Exception {
            drill.migrateTo("5");
            Connection request = requestStillWriting();

            Future<?> migration = background.submit(() -> drill.migrateTo("6"));
            awaitWaitingForLock("CREATE INDEX CONCURRENTLY");

            for (int i = 0; i < 3; i++) {
                assertTimeoutPreemptively(NOT_BLOCKED, MigrationLockTest.this::saveContact, "建索引期間存聯絡人被卡住了");
            }
            assertFalse(migration.isDone(), "V6 應該還在等那個寫到一半的請求");

            request.commit();
            migration.get(10, SECONDS);
        }

        @Test
        @DisplayName("反例：不帶 CONCURRENTLY 的 CREATE INDEX，建好之前存聯絡人會一直卡著")
        void plainCreateIndexBlocksWrites() throws Exception {
            drill.migrateTo("5");
            // 一般的 CREATE INDEX 整段拿著 SHARE 鎖。表很小一下就建完，所以包在交易裡不 commit，模擬「大表要建很久」
            Connection building = drill.connect();
            building.setAutoCommit(false);
            try (Statement s = building.createStatement()) {
                s.execute("CREATE INDEX drill_contact_phone_number_plain ON drill_contact (phone_number)");
            }

            Future<?> save = background.submit(MigrationLockTest.this::saveContact);
            await().during(NOT_BLOCKED).atMost(NOT_BLOCKED.plusSeconds(1)).until(() -> !save.isDone());

            building.rollback();
            save.get(10, SECONDS);
        }
    }

    @Nested
    @DisplayName("Flyway 自己的鎖：CONCURRENTLY 要配 transactional-lock=false")
    class FlywayLock {

        @Test
        @DisplayName("app 的設定（連線層級的鎖）：沒有別的請求時，V6 幾秒內跑完")
        void sessionLockFinishes() {
            drill.migrateTo("5");
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> drill.migrateTo("6"));
        }

        @Test
        @DisplayName("反例：Flyway 預設的交易層級鎖，沒有任何別的請求，V6 也永遠跑不完 —— 它在等 Flyway 自己")
        void transactionalLockWaitsForItself() {
            drill.migrateTo("5");
            Future<?> migration = background.submit(() -> drill.flyway()
                    .configuration(Map.of("flyway.postgresql.transactional.lock", "true"))
                    .target("6")
                    .load()
                    .migrate());

            awaitWaitingForLock("CREATE INDEX CONCURRENTLY");
            await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(6)).until(() -> !migration.isDone());
            // 卡住的那條連線由 @AfterEach 砍掉
        }
    }
}
