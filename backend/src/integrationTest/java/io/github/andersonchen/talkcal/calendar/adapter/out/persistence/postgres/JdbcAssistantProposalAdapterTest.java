package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JdbcAssistantProposalAdapter 對真的 PostgreSQL（做法同 JdbcCalendarEventAdapterTest，#223）。
 *
 * 重點是「每張卡片最多算一次」靠的是同一句 UPDATE 的 outcome IS NULL 條件 —— 同時兩個表態只有一個改得到，
 * 這只有真的資料庫驗得準。
 */
@Testcontainers
@DisplayName("JdbcAssistantProposalAdapter（真的 PostgreSQL）")
class JdbcAssistantProposalAdapterTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    static JdbcClient jdbc;
    static JdbcAssistantProposalAdapter adapter;

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final OwnerId OTHER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000002");

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        adapter = new JdbcAssistantProposalAdapter(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM assistant_proposal").update();
    }

    private static ProposalId issued(OwnerId owner) {
        ProposalId id = ProposalId.newId();
        adapter.saveProposals(owner, List.of(id));
        return id;
    }

    private static String outcomeOf(ProposalId id) {
        return jdbc.sql("SELECT outcome FROM assistant_proposal WHERE id = :id").param("id", id.value())
                .query(String.class).optional().orElse(null);
    }

    @Test
    @DisplayName("存下的卡片：自己第一次表態算數（true），寫進結果")
    void firstDecisionCounts() {
        ProposalId card = issued(OWNER);

        assertEquals(1, adapter.decide(OWNER, Set.of(card), ProposalOutcome.ADOPTED));
        assertEquals("ADOPTED", outcomeOf(card));
    }

    @Test
    @DisplayName("反例：已經表態過的（同樣的或相反的）、別人的、沒發過的，都是 false，結果不變")
    void otherDecisionsDoNotCount() {
        ProposalId card = issued(OWNER);
        ProposalId others = issued(OTHER);
        adapter.decide(OWNER, Set.of(card), ProposalOutcome.DISMISSED);

        assertEquals(0, adapter.decide(OWNER, Set.of(card), ProposalOutcome.DISMISSED));
        assertEquals(0, adapter.decide(OWNER, Set.of(card), ProposalOutcome.ADOPTED));
        assertEquals(0, adapter.decide(OWNER, Set.of(others), ProposalOutcome.ADOPTED));
        assertEquals(0, adapter.decide(OWNER, Set.of(ProposalId.newId()), ProposalOutcome.ADOPTED));

        assertEquals("DISMISSED", outcomeOf(card));
        assertEquals(null, outcomeOf(others), "別人的卡片不會被我改到");
    }

    @Test
    @DisplayName("一次核對好幾張：自己的、還沒表態過的算，別人的、沒發過的、已表態過的不算，回傳算數的張數")
    void decidesBatch() {
        ProposalId mine = issued(OWNER);
        ProposalId alsoMine = issued(OWNER);
        ProposalId decided = issued(OWNER);
        ProposalId others = issued(OTHER);
        adapter.decide(OWNER, Set.of(decided), ProposalOutcome.ADOPTED);

        assertEquals(2, adapter.decide(OWNER, Set.of(mine, alsoMine, decided, others, ProposalId.newId()), ProposalOutcome.DISMISSED));
        assertEquals("ADOPTED", outcomeOf(decided));
        assertEquals(null, outcomeOf(others));
    }

    @Test
    @DisplayName("一次發好幾張：一句寫進去，每張都記在 owner 名下")
    void savesSeveralAtOnce() {
        List<ProposalId> cards = List.of(ProposalId.newId(), ProposalId.newId(), ProposalId.newId());

        adapter.saveProposals(OWNER, cards);

        assertEquals(3, adapter.decide(OWNER, Set.copyOf(cards), ProposalOutcome.ADOPTED));
    }

    @Test
    @DisplayName("空的一批：0，不碰資料庫")
    void emptyBatchIsZero() {
        assertEquals(0, adapter.decide(OWNER, Set.of(), ProposalOutcome.ADOPTED));
    }

    @Test
    @DisplayName("同一張卡片同時被採納和拒絕：只有一個算數")
    void concurrentDecisionsCountOnce() throws Exception {
        for (int round = 0; round < 20; round++) {
            ProposalId card = issued(OWNER);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Boolean>> both = List.of(
                        () -> adapter.decide(OWNER, Set.of(card), ProposalOutcome.ADOPTED) == 1,
                        () -> adapter.decide(OWNER, Set.of(card), ProposalOutcome.DISMISSED) == 1);
                long counted = 0;
                for (Future<Boolean> result : pool.invokeAll(both)) {
                    if (result.get()) {
                        counted++;
                    }
                }
                assertEquals(1, counted, "第 " + round + " 輪");
            } finally {
                pool.shutdown();
            }
        }
    }

    @Test
    @DisplayName("清理排程：只刪 cutoff 之前發出的卡片")
    void deletesOnlyOldCards() {
        ProposalId old = issued(OWNER);
        ProposalId fresh = issued(OWNER);
        Instant cutoff = Instant.now().minusSeconds(3600);
        jdbc.sql("UPDATE assistant_proposal SET created_at = :at WHERE id = :id")
                .param("at", OffsetDateTime.ofInstant(cutoff.minusSeconds(60), ZoneOffset.UTC)).param("id", old.value()).update();

        assertEquals(1, adapter.deleteCreatedBefore(cutoff));
        assertEquals(0, adapter.decide(OWNER, Set.of(old), ProposalOutcome.ADOPTED), "刪掉的卡片不能再表態");
        assertEquals(1, adapter.decide(OWNER, Set.of(fresh), ProposalOutcome.ADOPTED));
    }

    @Test
    @DisplayName("資料庫連不上：存卡片、表態都不丟例外（記錄失敗不讓業務失敗），表態回 false")
    void databaseDownIsSwallowed() {
        JdbcAssistantProposalAdapter broken = new JdbcAssistantProposalAdapter(
                JdbcClient.create(new DriverManagerDataSource("jdbc:postgresql://localhost:1/none", "x", "x")));

        broken.saveProposals(OWNER, List.of(ProposalId.newId()));
        assertEquals(0, broken.decide(OWNER, Set.of(ProposalId.newId()), ProposalOutcome.ADOPTED));
    }

    @Test
    @DisplayName("表裡的 CHECK：沒有結果就不能有表態時間，結果只能是 ADOPTED／DISMISSED")
    void checksRejectInconsistentRows() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                        "INSERT INTO assistant_proposal (id, owner_id, outcome) VALUES (gen_random_uuid(), :owner, 'ADOPTED')")
                .param("owner", OWNER.value()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                        "INSERT INTO assistant_proposal (id, owner_id, outcome, decided_at) VALUES (gen_random_uuid(), :owner, 'MAYBE', now())")
                .param("owner", OWNER.value()).update());
    }
}
