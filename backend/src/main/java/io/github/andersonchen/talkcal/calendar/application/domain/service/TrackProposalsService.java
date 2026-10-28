package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DecideProposalPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveProposalsPort;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * 實作「記下提議卡片的去向」：發卡片時給 id、存起來；拒絕時逐張核對，核對過的才算進指標。
 */
public final class TrackProposalsService implements TrackProposalsUseCase {

    private final SaveProposalsPort saveProposals;
    private final DecideProposalPort decideProposal;
    private final RecordUsagePort usage;

    public TrackProposalsService(SaveProposalsPort saveProposals, DecideProposalPort decideProposal, RecordUsagePort usage) {
        this.saveProposals = Objects.requireNonNull(saveProposals, "saveProposals 不可為 null");
        this.decideProposal = Objects.requireNonNull(decideProposal, "decideProposal 不可為 null");
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
        saveProposals.saveProposals(owner, ids);
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
        // distinct：同一個請求裡重複的 id 也只問一次。真正擋重複的是 port（還沒表態過才算）
        long dismissed = proposals.stream().distinct()
                .filter(id -> decideProposal.decide(owner, id, ProposalOutcome.DISMISSED))
                .count();
        if (dismissed > 0) {
            usage.proposalsDismissed((int) dismissed);
        }
    }
}
