package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddedEvents;
import io.github.andersonchen.talkcal.calendar.application.port.in.IdempotencyKeyReusedException;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.AlreadySaved;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.KeyUsedForOtherContent;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.Saved;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AddEventsService")
class AddEventsServiceTest {

    private static final OwnerId OWNER = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0));
    private static final CalendarEvent DENTIST =
            CalendarEvent.startingAt("看牙醫", LocalDateTime.of(2026, 10, 14, 9, 0));

    private static final SaveEventsPort MUST_NOT_SAVE = (owner, events) -> fail("不該存檔");
    private static final SaveEventsOncePort MUST_NOT_SAVE_ONCE = (owner, key, events) -> fail("沒帶冪等鍵，不該走 saveOnce");
    private static final IdempotencyKey KEY = new IdempotencyKey("test-key");

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

        List<ScheduledEvent> result = new AddEventsService(port, MUST_NOT_SAVE_ONCE, proposals, usage).addEvents(OWNER, List.of(DINNER, DENTIST), List.of());

        assertEquals(1, port.calls);
        assertEquals(OWNER, port.owner);
        assertEquals(result, port.saved);
        assertEquals(List.of(DINNER, DENTIST), result.stream().map(ScheduledEvent::event).toList());
    }

    @Test
    @DisplayName("空清單：什麼都不做，不驚動資料庫")
    void emptyListDoesNothing() {
        assertTrue(new AddEventsService(MUST_NOT_SAVE, MUST_NOT_SAVE_ONCE, proposals, usage).addEvents(OWNER, List.of(), List.of()).isEmpty());
        assertEquals(List.of(), usage.events, "什麼都沒存，就什麼都不記");
    }

    @Test
    @DisplayName("擁有者是 null，行程或卡片清單是 null 或含 null，卡片比行程多：IllegalArgumentException，不存檔")
    void rejectsNulls() {
        AddEventsService service = new AddEventsService(MUST_NOT_SAVE, MUST_NOT_SAVE_ONCE, proposals, usage);

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

        new AddEventsService(port, MUST_NOT_SAVE_ONCE, proposals, usage).addEvents(OWNER, List.of(DINNER), List.of());

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
                () -> new AddEventsService(broken, MUST_NOT_SAVE_ONCE, proposals, usage).addEvents(OWNER, List.of(DINNER), List.of(card))));
        assertEquals(List.of(), usage.events, "沒存進去的不算採納");
        assertEquals(null, proposals.outcomeOf(card), "沒存進去，卡片也還沒表態，之後重試還能算");
    }

    @Test
    @DisplayName("#223 帶著自己的卡片 id 存檔：那幾筆算採納（ASSISTANT），其餘算手動（MANUAL），卡片標成已採納")
    void ownCardsCountAsAdopted() {
        ProposalId card = proposals.issue(OWNER);

        new AddEventsService(new RecordingPort(), MUST_NOT_SAVE_ONCE, proposals, usage).addEvents(OWNER, List.of(DINNER, DENTIST), List.of(card));

        assertEquals(List.of("ASSISTANT 1", "MANUAL 1"), usage.events);
        assertEquals(ProposalOutcome.ADOPTED, proposals.outcomeOf(card));
    }

    @Test
    @DisplayName("沒帶卡片 id：全部算手動")
    void noCardsIsManual() {
        new AddEventsService(new RecordingPort(), MUST_NOT_SAVE_ONCE, proposals, usage).addEvents(OWNER, List.of(DINNER), List.of());

        assertEquals(List.of("MANUAL 1"), usage.events);
    }

    @Test
    @DisplayName("反例：同一張卡片採納兩次（重送、或同一個請求帶兩次）只算第一次，之後的那筆算手動")
    void sameCardCountsOnce() {
        ProposalId card = proposals.issue(OWNER);
        AddEventsService service = new AddEventsService(new RecordingPort(), MUST_NOT_SAVE_ONCE, proposals, usage);

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

        new AddEventsService(port, MUST_NOT_SAVE_ONCE, proposals, usage).addEvents(OWNER, List.of(DINNER, DENTIST, DINNER),
                List.of(others, ProposalId.newId(), dismissed));

        assertEquals(3, port.saved.size());
        assertEquals(List.of("MANUAL 3"), usage.events);
        assertEquals(null, proposals.outcomeOf(others), "別人的卡片不會被我標成已採納");
    }

    /**
     * 帶冪等鍵的那條路（#51）。「同一個鍵真的只存一次」是資料庫的唯一約束保證的，
     * 在 JdbcCalendarEventAdapterTest 對真的 PostgreSQL 測；這裡只測 service 把 port 的三種結果翻成什麼、指標記不記。
     */
    @Nested
    @DisplayName("帶冪等鍵")
    class WithIdempotencyKey {

        @Test
        @DisplayName("第一次：交給 saveOnce（不是 save），回存好的那幾筆，replayed = false，照常記採納和用量")
        void firstTimeSaves() {
            ProposalId card = proposals.issue(OWNER);
            List<OwnerId> owners = new ArrayList<>();
            SaveEventsOncePort port = (owner, key, events) -> {
                owners.add(owner);
                assertEquals(KEY, key);
                return new Saved();
            };

            AddedEvents added = new AddEventsService(MUST_NOT_SAVE, port, proposals, usage)
                    .addEvents(OWNER, KEY, List.of(DINNER, DENTIST), List.of(card));

            assertFalse(added.replayed());
            assertEquals(List.of(DINNER, DENTIST), added.events().stream().map(ScheduledEvent::event).toList());
            assertEquals(List.of(OWNER), owners);
            assertEquals(ProposalOutcome.ADOPTED, proposals.outcomeOf(card));
            assertEquals(List.of("ASSISTANT 1", "MANUAL 1"), usage.events);
        }

        @Test
        @DisplayName("之前存過：回的是當時那幾筆（當時的 id），replayed = true，不再記採納和用量")
        void replayReturnsEarlierEventsWithoutCountingAgain() {
            ProposalId card = proposals.issue(OWNER);
            List<ScheduledEvent> earlier = List.of(ScheduledEvent.schedule(DINNER));
            SaveEventsOncePort port = (owner, key, events) -> new AlreadySaved(earlier);

            AddedEvents added = new AddEventsService(MUST_NOT_SAVE, port, proposals, usage)
                    .addEvents(OWNER, KEY, List.of(DINNER), List.of(card));

            assertTrue(added.replayed());
            assertEquals(earlier, added.events());
            // 反例在 noKeyCountsTheRetryAsManual：不帶鍵的重送會被算成一筆手動新增
            assertEquals(List.of(), usage.events);
            assertEquals(null, proposals.outcomeOf(card), "重送不碰卡片：第一次存的時候已經表態過了");
        }

        @Test
        @DisplayName("反例：不帶鍵時，同一張卡片送第二次會再存一份，而且被算成手動新增 —— 這就是 #51 要擋的")
        void noKeyCountsTheRetryAsManual() {
            ProposalId card = proposals.issue(OWNER);
            RecordingPort port = new RecordingPort();
            AddEventsService service = new AddEventsService(port, MUST_NOT_SAVE_ONCE, proposals, usage);

            service.addEvents(OWNER, List.of(DINNER), List.of(card));
            service.addEvents(OWNER, List.of(DINNER), List.of(card));

            assertEquals(2, port.calls);
            assertEquals(List.of("ASSISTANT 1", "MANUAL 1"), usage.events);
        }

        @Test
        @DisplayName("同一個鍵存過別的內容：IdempotencyKeyReusedException，不記用量")
        void otherContentIsRejected() {
            SaveEventsOncePort port = (owner, key, events) -> new KeyUsedForOtherContent();

            assertThrows(IdempotencyKeyReusedException.class,
                    () -> new AddEventsService(MUST_NOT_SAVE, port, proposals, usage).addEvents(OWNER, KEY, List.of(DENTIST), List.of()));
            assertEquals(List.of(), usage.events);
        }

        @Test
        @DisplayName("空清單：不記下這個鍵、不驚動資料庫")
        void emptyListDoesNothing() {
            AddedEvents added = new AddEventsService(MUST_NOT_SAVE, MUST_NOT_SAVE_ONCE, proposals, usage)
                    .addEvents(OWNER, KEY, List.of(), List.of());

            assertTrue(added.events().isEmpty());
            assertFalse(added.replayed());
        }

        @Test
        @DisplayName("鍵是 null、擁有者是 null、清單含 null、卡片比行程多：IllegalArgumentException，不存檔")
        void rejectsBadArguments() {
            AddEventsService service = new AddEventsService(MUST_NOT_SAVE, MUST_NOT_SAVE_ONCE, proposals, usage);

            assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, (IdempotencyKey) null, List.of(DINNER), List.of()));
            assertThrows(IllegalArgumentException.class, () -> service.addEvents(null, KEY, List.of(DINNER), List.of()));
            assertThrows(IllegalArgumentException.class, () -> service.addEvents(OWNER, KEY, Arrays.asList(DINNER, null), List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> service.addEvents(OWNER, KEY, List.of(DINNER), List.of(proposals.issue(OWNER), proposals.issue(OWNER))));
        }
    }
}
