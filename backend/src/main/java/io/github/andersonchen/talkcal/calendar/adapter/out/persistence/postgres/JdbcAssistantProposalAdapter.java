package io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.port.out.ProposalStorePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 助理發出去的提議卡片存在 PostgreSQL（表的定義在 V12__create_assistant_proposal.sql，#223）。
 *
 * port 的約定是「記錄失敗不丟例外」：卡片照樣顯示、行程照樣存好，只是那幾張的採納、拒絕不算進指標。
 * 所以這裡把資料庫的例外吞掉、留一筆 warn，不往外丟。
 *
 * 舊卡片由 AssistantMemoryCleanup 每天清（deleteCreatedBefore），保留期限跟對話一樣。
 */
public final class JdbcAssistantProposalAdapter implements ProposalStorePort {

    private static final Logger log = LoggerFactory.getLogger(JdbcAssistantProposalAdapter.class);

    private final JdbcClient jdbc;

    public JdbcAssistantProposalAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不可為 null");
    }

    @Override
    public void saveProposals(OwnerId owner, List<ProposalId> proposals) {
        Objects.requireNonNull(owner, "owner 不可為 null");
        Objects.requireNonNull(proposals, "proposals 不可為 null");
        try {
            // 一張一句：一則回覆的卡片只有幾張，不值得為了批次多一層寫法
            for (ProposalId proposal : proposals) {
                jdbc.sql("INSERT INTO assistant_proposal (id, owner_id) VALUES (:id, :owner)")
                        .param("id", proposal.value())
                        .param("owner", owner.value())
                        .update();
            }
        } catch (DataAccessException e) {
            log.warn("記不下助理發出的提議卡片，這幾張之後的採納、拒絕都不會算進指標：owner={} count={}", owner, proposals.size(), e);
        }
    }

    @Override
    public int decide(OwnerId owner, Set<ProposalId> proposals, ProposalOutcome outcome) {
        Objects.requireNonNull(owner, "owner 不可為 null");
        Objects.requireNonNull(proposals, "proposals 不可為 null");
        Objects.requireNonNull(outcome, "outcome 不可為 null");
        if (proposals.isEmpty()) {
            return 0;
        }
        try {
            // 一句改完整批，回傳改到幾列就是算數的張數。判斷和寫入在同一句：outcome IS NULL 是條件，
            // 同一張卡片同時被採納和拒絕，只有先更新的那句改得到它。
            // owner_id 也是條件：別人的卡片改不到，結果跟「沒有這張卡片」一樣是 0 列。
            // IN (:ids) 收 List，JdbcClient 會展開成一個 id 一個參數；張數由 use case 設了上限
            return jdbc.sql("""
                            UPDATE assistant_proposal
                            SET outcome = :outcome, decided_at = now()
                            WHERE id IN (:ids) AND owner_id = :owner AND outcome IS NULL
                            """)
                    .param("outcome", outcome.name())
                    .param("ids", proposals.stream().map(ProposalId::value).toList())
                    .param("owner", owner.value())
                    .update();
        } catch (DataAccessException e) {
            log.warn("記不下對提議卡片的表態，這次不算進指標：owner={} proposals={} outcome={}", owner, proposals, outcome, e);
            return 0;
        }
    }

    /** 刪掉 cutoff 之前發出的卡片，回傳刪了幾張。給清理排程用，不是 port。 */
    public int deleteCreatedBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff 不可為 null");
        return jdbc.sql("DELETE FROM assistant_proposal WHERE created_at < :cutoff")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
