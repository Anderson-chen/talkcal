package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DecideProposalPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import java.util.List;
import java.util.Objects;

/**
 * 實作「把確認過的行程排進行事曆」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則。
 *
 * 流程：每筆排進去（發 id）→ 一次存起來 → 核對帶來的提議卡片 → 記下用量（採納幾筆、手動幾筆）。
 * 用量在存好之後才記：存檔失敗的不算數，不然「採納」會把沒存進去的也算進來。
 * 採納不信呼叫端說的，一張一張問 DecideProposalPort：是 owner 的、還沒表態過的卡片才算（#223）。
 * 行程本身的規則已經在 CalendarEvent 建構時檢查過，這裡不再看第二次。
 */
public final class AddEventsService implements AddEventsUseCase {

    private final SaveEventsPort saveEventsPort;
    private final DecideProposalPort decideProposal;
    private final RecordUsagePort usage;

    public AddEventsService(SaveEventsPort saveEventsPort, DecideProposalPort decideProposal, RecordUsagePort usage) {
        this.saveEventsPort = Objects.requireNonNull(saveEventsPort, "saveEventsPort 不可為 null");
        this.decideProposal = Objects.requireNonNull(decideProposal, "decideProposal 不可為 null");
        this.usage = Objects.requireNonNull(usage, "usage 不可為 null");
    }

    @Override
    public List<ScheduledEvent> addEvents(OwnerId owner, List<CalendarEvent> events, List<ProposalId> adopting) {
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
        if (events.isEmpty()) {
            // 沒東西要存就不驚動資料庫
            return List.of();
        }
        List<ScheduledEvent> scheduled = events.stream().map(ScheduledEvent::schedule).toList();
        saveEventsPort.save(owner, scheduled);
        // distinct：同一個請求裡帶兩次同一張卡片也只問一次。真正擋重複的是 port（還沒表態過才算）
        int adopted = (int) adopting.stream().distinct()
                .filter(id -> decideProposal.decide(owner, id, ProposalOutcome.ADOPTED))
                .count();
        if (adopted > 0) {
            usage.eventsAdded(EventOrigin.ASSISTANT, adopted);
        }
        if (scheduled.size() > adopted) {
            usage.eventsAdded(EventOrigin.MANUAL, scheduled.size() - adopted);
        }
        return scheduled;
    }
}
