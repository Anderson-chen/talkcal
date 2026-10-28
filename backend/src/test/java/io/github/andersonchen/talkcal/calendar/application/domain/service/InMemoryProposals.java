package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.port.out.DecideProposalPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveProposalsPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 記憶體版的提議卡片存放，照 JdbcAssistantProposalAdapter 的規則演：
 * 是 owner 的、還沒表態過的卡片，表態才算（回 true）。真的 SQL 由 JdbcAssistantProposalAdapterTest 對 PostgreSQL 驗。
 */
public final class InMemoryProposals implements SaveProposalsPort, DecideProposalPort {

    private final Map<ProposalId, OwnerId> owners = new HashMap<>();
    private final Map<ProposalId, ProposalOutcome> outcomes = new HashMap<>();

    @Override
    public synchronized void saveProposals(OwnerId owner, List<ProposalId> proposals) {
        proposals.forEach(id -> owners.put(id, owner));
    }

    @Override
    public synchronized boolean decide(OwnerId owner, ProposalId proposal, ProposalOutcome outcome) {
        if (!owner.equals(owners.get(proposal)) || outcomes.containsKey(proposal)) {
            return false;
        }
        outcomes.put(proposal, outcome);
        return true;
    }

    /** 測試直接塞一張 owner 的卡片，不經過 service。 */
    public ProposalId issue(OwnerId owner) {
        ProposalId id = ProposalId.newId();
        saveProposals(owner, List.of(id));
        return id;
    }

    public synchronized ProposalOutcome outcomeOf(ProposalId proposal) {
        return outcomes.get(proposal);
    }

    public synchronized void clear() {
        owners.clear();
        outcomes.clear();
    }
}
