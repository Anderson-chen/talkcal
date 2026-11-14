package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.port.out.ProposalStorePort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 照 JdbcAssistantProposalAdapter 的規則演：是 owner 的、還沒表態過的卡片，表態才算。
 * 兩邊演得不一樣的話，用 InMemoryProposals 的 service 測試會描述不會發生的情境；SQL 本身見 JdbcAssistantProposalAdapterTest。
 */
public final class InMemoryProposals implements ProposalStorePort {

    private final Map<ProposalId, OwnerId> owners = new HashMap<>();
    private final Map<ProposalId, ProposalOutcome> outcomes = new HashMap<>();

    @Override
    public synchronized void saveProposals(OwnerId owner, List<ProposalId> proposals) {
        proposals.forEach(id -> owners.put(id, owner));
    }

    @Override
    public synchronized int decide(OwnerId owner, Set<ProposalId> proposals, ProposalOutcome outcome) {
        int decided = 0;
        for (ProposalId proposal : proposals) {
            if (owner.equals(owners.get(proposal)) && !outcomes.containsKey(proposal)) {
                outcomes.put(proposal, outcome);
                decided++;
            }
        }
        return decided;
    }

    public ProposalId issue(OwnerId owner) {
        ProposalId id = ProposalId.newId();
        saveProposals(owner, List.of(id));
        return id;
    }

    public synchronized ProposalOutcome outcomeOf(ProposalId proposal) {
        return outcomes.get(proposal);
    }
}
