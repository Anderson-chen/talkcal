package io.github.andersonchen.talkcal;

import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 整合測試自己跑 Flyway（不經過 Spring）時的共同設定，跟 application.properties 的 spring.flyway.* 對齊。
 *
 * 會有這個類別是因為 V14 的 CREATE INDEX CONCURRENTLY：Flyway 預設用交易層級的 advisory lock，
 * CONCURRENTLY 要等它自己拿著鎖的那個交易結束，互等就卡住不動（#18，實測過）。
 * app 那邊用 spring.flyway.postgresql.transactional-lock=false 關掉；測試直接 Flyway.configure() 的話吃不到那個設定，
 * 每支測試各寫一次很容易漏 —— 漏的那支不會失敗，而是整個整合測試卡到逾時。
 */
public final class Migrations {

    private Migrations() {
    }

    /** 對這個容器的 Flyway 設定；要停在某一版（例如測 backfill）就再接 .target(...)。 */
    public static FluentConfiguration flyway(PostgreSQLContainer postgres) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
    }

    /** 跑到最新版。 */
    public static void migrate(PostgreSQLContainer postgres) {
        flyway(postgres).load().migrate();
    }
}
