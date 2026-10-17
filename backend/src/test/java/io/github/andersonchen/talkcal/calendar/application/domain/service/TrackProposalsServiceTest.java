package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrackProposalsService")
class TrackProposalsServiceTest {

    private final List<String> recorded = new ArrayList<>();

    private final TrackProposalsService service = new TrackProposalsService(new RecordUsagePort() {
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
    @DisplayName("給人看的、被拒絕的各記各的；拒絕不會記成採納（沒有 added）")
    void recordsShownAndDismissedSeparately() {
        service.proposalsShown(3);
        service.proposalsDismissed(1);

        assertEquals(List.of("shown 3", "dismissed 1"), recorded);
    }

    @Test
    @DisplayName("0 張：什麼都不記（沒有提議的回覆不該讓指標動）")
    void zeroRecordsNothing() {
        service.proposalsShown(0);
        service.proposalsDismissed(0);

        assertEquals(List.of(), recorded);
    }

    @Test
    @DisplayName("負的、拒絕數超過上限：IllegalArgumentException，什麼都不記；剛好到上限可以")
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> service.proposalsShown(-1));
        assertThrows(IllegalArgumentException.class, () -> service.proposalsDismissed(-1));
        assertThrows(IllegalArgumentException.class,
                () -> service.proposalsDismissed(TrackProposalsUseCase.MAX_DISMISSED_PER_CALL + 1));
        assertEquals(List.of(), recorded);

        service.proposalsDismissed(TrackProposalsUseCase.MAX_DISMISSED_PER_CALL);
        assertEquals(List.of("dismissed " + TrackProposalsUseCase.MAX_DISMISSED_PER_CALL), recorded);
    }

    @Test
    @DisplayName("給人看的張數沒有上限：那是伺服器自己數的，一則回覆卡片多也不該讓回覆失敗")
    void shownHasNoCap() {
        int many = TrackProposalsUseCase.MAX_DISMISSED_PER_CALL + 5;

        service.proposalsShown(many);

        assertEquals(List.of("shown " + many), recorded);
    }
}
