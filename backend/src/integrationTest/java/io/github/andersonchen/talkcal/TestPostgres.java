package io.github.andersonchen.talkcal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 整合測試共用一個 PostgreSQL 容器，每個測試 class 從裡面拿一個新的空資料庫。
 *
 * 共用容器省下每個 class 開關容器的時間。每個 class 各用一個資料庫：一個 class 寫入的資料，
 * 其他 class 讀不到；Flyway 也從空的資料庫開始執行，所以能測出 migration 從頭執行一遍會不會失敗。
 * 容器在第一次用到時啟動，JVM 結束時由 Testcontainers 停止並移除。
 *
 * {@code @SpringBootTest} 的 class 各自在 {@code @DynamicPropertySource} 呼叫 {@link #newDatabase()}。
 * 不要搬到共用的父類別：設定一樣的兩個 class 會被 Spring 當成同一個 context，共用同一個資料庫。
 */
public final class TestPostgres {

    private static final AtomicInteger COUNT = new AtomicInteger();

    private static PostgreSQLContainer server;
    private static RuntimeException startFailure;

    private TestPostgres() {
    }

    /** 建立新的空資料庫，還沒執行 migration。 */
    public static Database newDatabase() {
        PostgreSQLContainer postgres = server();
        String name = "test_" + COUNT.incrementAndGet();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("無法建立測試資料庫 " + name, e);
        }
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + name;
        return new Database(url, postgres.getUsername(), postgres.getPassword());
    }

    // 不在 static 區塊啟動：啟動失敗（例如 Docker 沒開）的話，只有第一個 class 收到啟動時的例外，
    // 之後的 class 只收到 NoClassDefFoundError，看不出是 Docker 的問題。所以這裡記下第一次的例外，之後每個 class 呼叫時都丟同一個
    private static synchronized PostgreSQLContainer server() {
        if (startFailure != null) {
            throw startFailure;
        }
        if (server == null) {
            PostgreSQLContainer container = new PostgreSQLContainer("postgres:18.6-alpine");
            try {
                container.start();
            } catch (RuntimeException e) {
                startFailure = e;
                throw e;
            }
            server = container;
        }
        return server;
    }

    public record Database(String url, String username, String password) {

        /** 執行 {@code src/main/resources/db/migration} 的全部 migration，跟 app 啟動時一樣。 */
        public Database migrated() {
            Flyway.configure().dataSource(url, username, password).load().migrate();
            return this;
        }

        /** 只執行到 version 這一版為止，用來在新的 migration 執行前先塞入舊格式的資料。 */
        public Database migratedTo(String version) {
            Flyway.configure().dataSource(url, username, password).target(version).load().migrate();
            return this;
        }

        public DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource(url, username, password);
        }

        /** 讓 {@code @SpringBootTest} 的 app 連這個資料庫；app 啟動時自己執行 Flyway。 */
        public void register(DynamicPropertyRegistry registry) {
            registry.add("spring.datasource.url", this::url);
            registry.add("spring.datasource.username", this::username);
            registry.add("spring.datasource.password", this::password);
            // Spring 把每個 context 快取到測試結束，連線池也一直開著。Hikari 預設每個連線池 10 條連線，
            // 系統測試 class 超過 10 個，就會超過 PostgreSQL 預設的 100 條連線上限
            registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        }
    }
}
