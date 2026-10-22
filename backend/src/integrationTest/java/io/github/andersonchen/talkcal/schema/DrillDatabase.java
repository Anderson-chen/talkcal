package io.github.andersonchen.talkcal.schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 演練用的資料庫：同一個容器裡每支測試開一個自己的，跑的是 db/drill 的 migration（#18）。
 *
 * 每支測試一個，是因為有幾支會故意把 migration 弄到失敗或卡住，不能留給下一支。
 * close() 先砍掉這個資料庫上的所有連線：故意卡住的 migration 會拿到例外結束，不會留在背景。
 */
final class DrillDatabase implements AutoCloseable {

    private final PostgreSQLContainer postgres;
    private final String name;
    private final String url;
    private final List<Connection> connections = new ArrayList<>();

    private DrillDatabase(PostgreSQLContainer postgres, String name) {
        this.postgres = postgres;
        this.name = name;
        this.url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
    }

    static DrillDatabase create(PostgreSQLContainer postgres) throws SQLException {
        String name = "drill_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = admin(postgres); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return new DrillDatabase(postgres, name);
    }

    private static Connection admin(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /**
     * 跟 app 一樣的 Flyway 設定（application.properties 的 spring.flyway.postgresql.transactional-lock=false），只是換成演練的 migration。
     * 少了 transactional.lock=false，V6 的 CREATE INDEX CONCURRENTLY 會等 Flyway 自己的交易、整支測試卡到逾時。
     */
    FluentConfiguration flyway() {
        return Flyway.configure()
                .dataSource(url, postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/drill")
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
    }

    void migrateTo(String version) {
        flyway().target(version).load().migrate();
    }

    String url() {
        return url;
    }

    /** 一條自己的連線，close() 時一起關。 */
    Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
        connections.add(c);
        return c;
    }

    /** 每次操作開一條新連線、用完就還，跟 app 的連線池一樣不會被別的測試連線上沒結束的交易拖住。 */
    JdbcClient jdbc() {
        return JdbcClient.create(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    }

    @Override
    public void close() throws SQLException {
        try (Connection admin = admin(postgres);
             PreparedStatement terminate = admin.prepareStatement(
                     "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = ?")) {
            terminate.setString(1, name);
            terminate.execute();
        }
        for (Connection c : connections) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 已經被上面砍掉了
            }
        }
    }
}
