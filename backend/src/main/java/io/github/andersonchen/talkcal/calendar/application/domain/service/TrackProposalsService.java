package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.ProposalStorePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 實作「記下提議卡片的去向」：發卡片時給 id、存起來；拒絕時整批交給 port 核對，核對過的才算進指標。
 */
public final class TrackProposalsService implements TrackProposalsUseCase {

    private final ProposalStorePort store;
    private final RecordUsagePort usage;

    public TrackProposalsService(ProposalStorePort store, RecordUsagePort usage) {
        this.store = Objects.requireNonNull(store, "store 不可為 null");
        this.usage = Objects.requireNonNull(usage, "usage 不可為 null");
    }

    @Override
    public List<ProposalId> proposalsShown(OwnerId owner, int count) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        if (count < 0) {
            throw new IllegalArgumentException("卡片數不可為負：" + count);
        }
        if (count == 0) {
            return List.of();
        }
        List<ProposalId> ids = IntStream.range(0, count).mapToObj(i -> ProposalId.newId()).toList();
        store.saveProposals(owner, ids);
        usage.proposalsShown(count);
        return ids;
    }

    @Override
    public void proposalsDismissed(OwnerId owner, List<ProposalId> proposals) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        if (proposals == null || proposals.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("卡片清單和裡面的每一張都不可為 null");
        }
        if (proposals.size() > MAX_DISMISSED_PER_CALL) {
            throw new IllegalArgumentException("一次最多回報 " + MAX_DISMISSED_PER_CALL + " 張卡片：" + proposals.size());
        }
        // Set：同一個請求裡重複的 id 只算一張。跨請求的重複由 port 擋（還沒表態過才算）
        int dismissed = store.decide(owner, Set.copyOf(proposals), ProposalOutcome.DISMISSED);
        if (dismissed > 0) {
            usage.proposalsDismissed(dismissed);
        }
    }
}
