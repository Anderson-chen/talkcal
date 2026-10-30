package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddedEvents;
import io.github.andersonchen.talkcal.calendar.application.port.in.IdempotencyKeyReusedException;
import io.github.andersonchen.talkcal.calendar.application.port.out.ProposalStorePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.AlreadySaved;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.KeyUsedForOtherContent;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.Saved;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 實作「把確認過的行程排進行事曆」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則。
 *
 * 流程：每筆排進去（發 id）→ 一次存起來 → 核對帶來的提議卡片 → 記下用量（採納幾筆、手動幾筆）。
 * 用量在存好之後才記：存檔失敗的不算數，不然「採納」會把沒存進去的也算進來。
 * 採納不信呼叫端說的，交給 ProposalStorePort 核對：是 owner 的、還沒表態過的卡片才算（#223）。
 * 行程本身的規則已經在 CalendarEvent 建構時檢查過，這裡不再看第二次。
 *
 * 帶冪等鍵時改走 SaveEventsOncePort：「這個鍵存過沒有」由它在存的那一刻決定，這裡只把三種結果翻成 use case 的回答。
 * 重送（之前存過）不再記採納和用量：那幾筆第一次存的時候已經記過了，再記一次會把同一筆算成兩筆
 * （卡片已經表態過，第二次會被算成手動新增）。
 */
public final class AddEventsService implements AddEventsUseCase {

    private final SaveEventsPort saveEventsPort;
    private final SaveEventsOncePort saveEventsOncePort;
    private final ProposalStorePort proposals;
    private final RecordUsagePort usage;

    public AddEventsService(SaveEventsPort saveEventsPort, SaveEventsOncePort saveEventsOncePort,
                            ProposalStorePort proposals, RecordUsagePort usage) {
        this.saveEventsPort = Objects.requireNonNull(saveEventsPort, "saveEventsPort 不可為 null");
        this.saveEventsOncePort = Objects.requireNonNull(saveEventsOncePort, "saveEventsOncePort 不可為 null");
        this.proposals = Objects.requireNonNull(proposals, "proposals 不可為 null");
        this.usage = Objects.requireNonNull(usage, "usage 不可為 null");
    }

    @Override
    public List<ScheduledEvent> addEvents(OwnerId owner, List<CalendarEvent> events, List<ProposalId> adopting) {
        checkArguments(owner, events, adopting);
        if (events.isEmpty()) {
            // 沒東西要存就不驚動資料庫
            return List.of();
        }
        List<ScheduledEvent> scheduled = events.stream().map(ScheduledEvent::schedule).toList();
        saveEventsPort.save(owner, scheduled);
        recordAdded(owner, scheduled.size(), adopting);
        return scheduled;
    }

    @Override
    public AddedEvents addEvents(OwnerId owner, IdempotencyKey key, List<CalendarEvent> events, List<ProposalId> adopting) {
        checkArguments(owner, events, adopting);
        if (key == null) {
            throw new IllegalArgumentException("冪等鍵不可為 null（不帶鍵就呼叫另一個 addEvents）");
        }
        if (events.isEmpty()) {
            // 空的一批不記下這個鍵：沒有東西會重複，記了只是佔位置
            return new AddedEvents(List.of(), false);
        }
        List<ScheduledEvent> scheduled = events.stream().map(ScheduledEvent::schedule).toList();
        return switch (saveEventsOncePort.saveOnce(owner, key, scheduled)) {
            case Saved ignored -> {
                recordAdded(owner, scheduled.size(), adopting);
                yield new AddedEvents(scheduled, false);
            }
            case AlreadySaved earlier -> new AddedEvents(earlier.events(), true);
            case KeyUsedForOtherContent ignored -> throw new IdempotencyKeyReusedException(key);
        };
    }

    private static void checkArguments(OwnerId owner, List<CalendarEvent> events, List<ProposalId> adopting) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        // 這是呼叫端違約（契約說不可為 null），不是業務規則，所以在這裡擋、而不是在 model。
        // 丟 IllegalArgumentException 而不是 NullPointerException：跟其他「輸入不合規」同一種，HTTP 那頭對應 400
        // 不用 events.contains(null)：List.of 做出來的不可變清單，contains(null) 會直接丟 NullPointerException
        if (events == null || events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("行程清單和裡面的每一筆都不可為 null");
        }
        if (adopting == null || adopting.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("卡片清單和裡面的每一張都不可為 null");
        }
        if (adopting.size() > events.size()) {
            // 一筆行程最多來自一張卡片：多出來的 id 是在拿一筆行程刷好幾張卡片的採納
            throw new IllegalArgumentException("卡片比行程多：" + adopting.size() + " 張卡片、" + events.size() + " 筆行程");
        }
    }

    // 存好之後才呼叫：核對卡片、記下採納幾筆、手動幾筆
    private void recordAdded(OwnerId owner, int saved, List<ProposalId> adopting) {
        // Set：同一個請求裡帶兩次同一張卡片只算一張。跨請求的重複由 port 擋（還沒表態過才算）
        int adopted = adopting.isEmpty() ? 0 : proposals.decide(owner, Set.copyOf(adopting), ProposalOutcome.ADOPTED);
        if (adopted > 0) {
            usage.eventsAdded(EventOrigin.ASSISTANT, adopted);
        }
        if (saved > adopted) {
            usage.eventsAdded(EventOrigin.MANUAL, saved - adopted);
        }
    }
}
