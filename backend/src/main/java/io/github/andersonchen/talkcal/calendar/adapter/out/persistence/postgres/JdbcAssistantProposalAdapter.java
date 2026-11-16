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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 表的定義在 V12__create_assistant_proposal.sql。
 *
 * port 約定記錄失敗不丟例外，所以這裡吞掉資料庫的例外、留一筆 warn。
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
            if (proposals.isEmpty()) {
                return;
            }
            // 一句多列 INSERT：整批全有或全無，不會留下部分卡片，也只來回資料庫一次。
            // 列數跟著卡片數變，但每個值都是參數（:id0、:id1…）
            String rows = IntStream.range(0, proposals.size())
                    .mapToObj(i -> "(:id" + i + ", :owner)")
                    .collect(Collectors.joining(", "));
            JdbcClient.StatementSpec insert = jdbc.sql("INSERT INTO assistant_proposal (id, owner_id) VALUES " + rows)
                    .param("owner", owner.value());
            for (int i = 0; i < proposals.size(); i++) {
                insert = insert.param("id" + i, proposals.get(i).value());
            }
            insert.update();
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
            // 一句改完整批，改到幾列就是算數的張數。outcome IS NULL 是條件，判斷和寫入在同一句：
            // 同一張卡片同時被採納和拒絕，只有先更新的那句改得到。owner_id 也是條件，別人的卡片改不到，跟沒有這張卡片一樣是 0 列。
            // IN (:ids) 收 List，JdbcClient 會展開成一個 id 一個參數；張數上限在 use case
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

    /** 不是 port：這張表自己的維護工作，由歸檔排程直接呼叫。卡片只用在產品指標，不歸檔。 */
    public int deleteCreatedBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff 不可為 null");
        return jdbc.sql("DELETE FROM assistant_proposal WHERE created_at < :cutoff")
                .param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
                .update();
    }
}
