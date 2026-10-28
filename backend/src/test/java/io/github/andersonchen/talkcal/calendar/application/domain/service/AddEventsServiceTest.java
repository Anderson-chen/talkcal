package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AddEventsService")
class AddEventsServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0));
    private static final CalendarEvent DENTIST =
            CalendarEvent.startingAt("看牙醫", LocalDateTime.of(2026, 10, 14, 9, 0));

    private static final SaveEventsPort MUST_NOT_SAVE = (owner, events) -> fail("不該存檔");

    // 假的用量紀錄：記成「ASSISTANT 2」這種字串
    private static final class RecordingUsage implements RecordUsagePort {
        final List<String> events = new ArrayList<>();

        @Override
        public void proposalsShown(int count) {
            events.add("shown " + count);
        }

        @Override
        public void proposalsDismissed(int count) {
            events.add("dismissed " + count);
        }

        @Override
        public void eventsAdded(EventOrigin origin, int count) {
            events.add(origin + " " + count);
        }
    }

    private final RecordingUsage usage = new RecordingUsage();
    private final InMemoryProposals proposals = new InMemoryProposals();
    private static final OwnerId OTHER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000002");

    // 假資料庫：記下每次收到的那一批
    private static final class RecordingPort implements SaveEventsPort {
        int calls;
        OwnerId owner;
        List<ScheduledEvent> saved;

        @Override
        public void save(OwnerId owner, List<ScheduledEvent> events) {
            calls++;
            this.owner = owner;
            saved = events;
        }
    }

    @Test
    @DisplayName("多筆行程一次存起來，每筆都發了 ID，順序不變，存在 owner 名下")
    void schedulesAndSavesAllInOneCall() {
        RecordingPort port = new RecordingPort();

        List<ScheduledEvent> result = new AddEventsService(port, proposals, usage).addEvents(OWNER, List.of(DINNER, DENTIST), List.of());

        assertEquals(1, port.calls);
        assertEquals(OWNER, port.owner);
        assertEquals(result, port.saved);
        assertEquals(List.of(DINNER, DENTIST), result.stream().map(ScheduledEvent::event).toList());
    }

    @Test
    @DisplayName("空清單：什麼都不做，不驚動資料庫")
    void emptyListDoesNothing() {
        assertTrue(new AddEventsService(MUST_NOT_SAVE, proposals, usage).addEvents(OWNER, List.of(), List.of()).isEmpty());
        assertEquals(List.of(), usage.events, "什麼都沒存，就什麼都不記");
    }

    @Test
    @DisplayName("擁有者是 null，行程或卡片清單是 null 或含 null，卡片比行程多：IllegalArgumentException，不存檔")
    void rejectsNulls() {
        AddEventsService service = new AddEventsService(MUST_NOT_SAVE, proposals, usage);

        ProposalId card = proposals.issue(OWNER);
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(null, List.of(DINNER), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, Arrays.asList(DINNER, null), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, List.of(DINNER), null));
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, List.of(DINNER), Arrays.asList(card, null)));
        // 一筆行程刷兩張卡片的採納
        assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, List.of(DINNER), List.of(card, proposals.issue(OWNER))));
        assertEquals(null, proposals.outcomeOf(card), "被擋下的請求不能把卡片標成已表態");
    }

    @Test
    @DisplayName("不可變清單也能檢查 null（List.of 的 contains(null) 會丟 NullPointerException）")
    void worksWithImmutableLists() {
        RecordingPort port = new RecordingPort();

        new AddEventsService(port, proposals, usage).addEvents(OWNER, List.of(DINNER), List.of());

        assertEquals(1, port.saved.size());
    }

    @Test
    @DisplayName("資料庫出事時，port 的例外原樣往外丟")
    void propagatesPortFailure() {
        IllegalStateException failure = new IllegalStateException("資料庫連不上");
        SaveEventsPort broken = (owner, events) -> {
            throw failure;
        };
        ProposalId card = proposals.issue(OWNER);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new AddEventsService(broken, proposals, usage).addEvents(OWNER, List.of(DINNER), List.of(card))));
        assertEquals(List.of(), usage.events, "沒存進去的不算採納");
        assertEquals(null, proposals.outcomeOf(card), "沒存進去，卡片也還沒表態，之後重試還能算");
    }

    @Test
    @DisplayName("#223 帶著自己的卡片 id 存檔：那幾筆算採納（ASSISTANT），其餘算手動（MANUAL），卡片標成已採納")
    void ownCardsCountAsAdopted() {
        ProposalId card = proposals.issue(OWNER);

        new AddEventsService(new RecordingPort(), proposals, usage).addEvents(OWNER, List.of(DINNER, DENTIST), List.of(card));

        assertEquals(List.of("ASSISTANT 1", "MANUAL 1"), usage.events);
        assertEquals(ProposalOutcome.ADOPTED, proposals.outcomeOf(card));
    }

    @Test
    @DisplayName("沒帶卡片 id：全部算手動")
    void noCardsIsManual() {
        new AddEventsService(new RecordingPort(), proposals, usage).addEvents(OWNER, List.of(DINNER), List.of());

        assertEquals(List.of("MANUAL 1"), usage.events);
    }

    @Test
    @DisplayName("反例：同一張卡片採納兩次（重送、或同一個請求帶兩次）只算第一次，之後的那筆算手動")
    void sameCardCountsOnce() {
        ProposalId card = proposals.issue(OWNER);
        AddEventsService service = new AddEventsService(new RecordingPort(), proposals, usage);

        service.addEvents(OWNER, List.of(DINNER), List.of(card));
        service.addEvents(OWNER, List.of(DINNER), List.of(card));
        ProposalId twice = proposals.issue(OWNER);
        service.addEvents(OWNER, List.of(DINNER, DENTIST), List.of(twice, twice));

        assertEquals(List.of("ASSISTANT 1", "MANUAL 1", "ASSISTANT 1", "MANUAL 1"), usage.events);
    }

    @Test
    @DisplayName("反例：別人的卡片、沒發過的 id、已經拒絕過的卡片都不算採納，行程照樣存（算手動）")
    void foreignOrUnknownOrDismissedCardsAreManual() {
        ProposalId others = proposals.issue(OTHER);
        ProposalId dismissed = proposals.issue(OWNER);
        proposals.decide(OWNER, java.util.Set.of(dismissed), ProposalOutcome.DISMISSED);
        RecordingPort port = new RecordingPort();

        new AddEventsService(port, proposals, usage).addEvents(OWNER, List.of(DINNER, DENTIST, DINNER),
                List.of(others, ProposalId.newId(), dismissed));

        assertEquals(3, port.saved.size());
        assertEquals(List.of("MANUAL 3"), usage.events);
        assertEquals(null, proposals.outcomeOf(others), "別人的卡片不會被我標成已採納");
    }
}
