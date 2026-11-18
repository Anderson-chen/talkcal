package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.andersonchen.talkcal.TestPostgres;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 做法見 JdbcCalendarEventAdapterTest。上限在同時送來的請求之間也要守得住，要對真的 PostgreSQL 才驗得準：
 * 每個執行緒從連線池拿自己的連線，跟好幾個 app 副本同時寫同一張表一樣。
 */
@DisplayName("JdbcGuestDemoUsageAdapter（真的 PostgreSQL）")
class JdbcGuestDemoUsageAdapterTest {

    static final TestPostgres.Database DATABASE = TestPostgres.newDatabase().migrated();

    static JdbcClient jdbc;
    static JdbcGuestDemoUsageAdapter adapter;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);
    private static final String GUEST_IP = "203.0.113.7";

    @BeforeAll
    static void connect() {
        jdbc = JdbcClient.create(DATABASE.dataSource());
        adapter = new JdbcGuestDemoUsageAdapter(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM guest_demo_usage").update();
    }

    @Test
    @DisplayName("沒到上限就加一並回傳次數，到了回 empty；反例：別的 IP、隔天各自從 1 開始")
    void countsUpToTheLimit() {
        assertEquals(OptionalInt.of(1), adapter.use(GUEST_IP, TODAY, 3));
        assertEquals(OptionalInt.of(2), adapter.use(GUEST_IP, TODAY, 3));
        assertEquals(OptionalInt.of(3), adapter.use(GUEST_IP, TODAY, 3));
        assertEquals(OptionalInt.empty(), adapter.use(GUEST_IP, TODAY, 3));

        assertEquals(OptionalInt.of(1), adapter.use("198.51.100.4", TODAY, 3));
        assertEquals(OptionalInt.of(1), adapter.use(GUEST_IP, TODAY.plusDays(1), 3));
    }

    @Test
    @DisplayName("同一個 IP 同時送 20 個請求，只有 3 個成功，表裡也是 3")
    void concurrentRequestsDoNotOvershoot() throws Exception {
        int requests = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<OptionalInt>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return adapter.use(GUEST_IP, TODAY, 3);
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<OptionalInt> result : results) {
                if (result.get().isPresent()) {
                    succeeded++;
                }
            }
            assertEquals(3, succeeded);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(3, jdbc.sql("SELECT used FROM guest_demo_usage").query(Integer.class).single());
    }

    @Test
    @DisplayName("清理只刪指定日期以前的；反例：當天的留著，次數不會被清回 0")
    void deletesOnlyOlderDays() {
        adapter.use(GUEST_IP, TODAY.minusDays(1), 3);
        adapter.use(GUEST_IP, TODAY, 3);

        assertEquals(1, adapter.deleteBefore(TODAY));
        assertEquals(OptionalInt.of(2), adapter.use(GUEST_IP, TODAY, 3));
    }
}
