package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrackProposalsService")
class TrackProposalsServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final OwnerId OTHER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000002");

    private final List<String> recorded = new ArrayList<>();
    private final InMemoryProposals proposals = new InMemoryProposals();

    private final TrackProposalsService service = new TrackProposalsService(proposals, proposals, new RecordUsagePort() {
        @Override
        public void proposalsShown(int count) {
            recorded.add("shown " + count);
        }

        @Override
        public void proposalsDismissed(int count) {
            recorded.add("dismissed " + count);
        }

        @Override
        public void eventsAdded(EventOrigin origin, int count) {
            recorded.add("added " + origin + " " + count);
        }
    });

    @Test
    @DisplayName("給人看幾張就發幾個不同的 id、記在 owner 名下，指標記 shown")
    void issuesIdsForShownCards() {
        List<ProposalId> ids = service.proposalsShown(OWNER, 2);

        assertEquals(2, ids.size());
        assertNotEquals(ids.get(0), ids.get(1));
        assertEquals(List.of("shown 2"), recorded);
        assertEquals(true, proposals.decide(OWNER, ids.get(0), ProposalOutcome.ADOPTED), "發出去的卡片要存在、是 owner 的");
    }

    @Test
    @DisplayName("0 張：不發 id、不記指標；負的：IllegalArgumentException")
    void zeroAndNegative() {
        assertEquals(List.of(), service.proposalsShown(OWNER, 0));
        assertThrows(IllegalArgumentException.class, () -> service.proposalsShown(OWNER, -1));
        assertEquals(List.of(), recorded);
    }

    @Test
    @DisplayName("拒絕自己的卡片：每張算一次，卡片標成已拒絕；拒絕不會記成採納（沒有 added）")
    void dismissesOwnCards() {
        List<ProposalId> ids = service.proposalsShown(OWNER, 2);

        service.proposalsDismissed(OWNER, ids);

        assertEquals(List.of("shown 2", "dismissed 2"), recorded);
        assertEquals(ProposalOutcome.DISMISSED, proposals.outcomeOf(ids.get(0)));
    }

    @Test
    @DisplayName("反例：同一張拒絕兩次、同一個請求帶兩次、先採納再拒絕，都只算第一次")
    void eachCardCountsOnce() {
        List<ProposalId> ids = service.proposalsShown(OWNER, 2);
        proposals.decide(OWNER, ids.get(1), ProposalOutcome.ADOPTED);

        service.proposalsDismissed(OWNER, List.of(ids.get(0), ids.get(0)));
        service.proposalsDismissed(OWNER, List.of(ids.get(0)));
        service.proposalsDismissed(OWNER, List.of(ids.get(1)));

        assertEquals(List.of("shown 2", "dismissed 1"), recorded);
        assertEquals(ProposalOutcome.ADOPTED, proposals.outcomeOf(ids.get(1)), "已採納的不會被改成拒絕");
    }

    @Test
    @DisplayName("反例：別人的卡片、沒發過的 id 都不算，也不報錯（不透露別人的卡片存不存在）")
    void foreignAndUnknownCardsAreIgnored() {
        ProposalId others = service.proposalsShown(OTHER, 1).getFirst();
        recorded.clear();

        service.proposalsDismissed(OWNER, List.of(others, ProposalId.newId()));

        assertEquals(List.of(), recorded);
        assertEquals(null, proposals.outcomeOf(others));
    }

    @Test
    @DisplayName("擁有者是 null、清單是 null 或含 null：IllegalArgumentException")
    void rejectsNulls() {
        assertThrows(IllegalArgumentException.class, () -> service.proposalsShown(null, 1));
        assertThrows(IllegalArgumentException.class, () -> service.proposalsDismissed(null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.proposalsDismissed(OWNER, null));
        assertThrows(IllegalArgumentException.class, () -> service.proposalsDismissed(OWNER, Arrays.asList(ProposalId.newId(), null)));
    }
}
