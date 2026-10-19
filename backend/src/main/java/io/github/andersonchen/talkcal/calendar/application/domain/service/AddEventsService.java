package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddedEvents;
import io.github.andersonchen.talkcal.calendar.application.port.in.IdempotencyKeyReusedException;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.AlreadySaved;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.KeyUsedForOtherContent;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort.Saved;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

import java.util.List;
import java.util.Objects;

/**
 * 實作「把確認過的行程排進行事曆」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則。
 *
 * 流程只有兩步：每筆排進去（發 id）→ 一次存起來。
 * 行程本身的規則已經在 CalendarEvent 建構時檢查過，這裡不再看第二次。
 *
 * 帶冪等鍵時改走 SaveEventsOncePort：「這個鍵存過沒有」由它在存的那一刻決定，這裡只把三種結果翻成 use case 的回答。
 */
public final class AddEventsService implements AddEventsUseCase {

    private final SaveEventsPort saveEventsPort;
    private final SaveEventsOncePort saveEventsOncePort;

    public AddEventsService(SaveEventsPort saveEventsPort, SaveEventsOncePort saveEventsOncePort) {
        this.saveEventsPort = Objects.requireNonNull(saveEventsPort, "saveEventsPort 不可為 null");
        this.saveEventsOncePort = Objects.requireNonNull(saveEventsOncePort, "saveEventsOncePort 不可為 null");
    }

    @Override
    public List<ScheduledEvent> addEvents(OwnerId owner, List<CalendarEvent> events) {
        checkArguments(owner, events);
        if (events.isEmpty()) {
            // 沒東西要存就不驚動資料庫
            return List.of();
        }
        List<ScheduledEvent> scheduled = events.stream().map(ScheduledEvent::schedule).toList();
        saveEventsPort.save(owner, scheduled);
        return scheduled;
    }

    @Override
    public AddedEvents addEvents(OwnerId owner, IdempotencyKey key, List<CalendarEvent> events) {
        checkArguments(owner, events);
        if (key == null) {
            throw new IllegalArgumentException("冪等鍵不可為 null（不帶鍵就呼叫另一個 addEvents）");
        }
        if (events.isEmpty()) {
            // 空的一批不記下這個鍵：沒有東西會重複，記了只是佔位置
            return new AddedEvents(List.of(), false);
        }
        List<ScheduledEvent> scheduled = events.stream().map(ScheduledEvent::schedule).toList();
        return switch (saveEventsOncePort.saveOnce(owner, key, scheduled)) {
            case Saved saved -> new AddedEvents(saved.events(), false);
            case AlreadySaved earlier -> new AddedEvents(earlier.events(), true);
            case KeyUsedForOtherContent ignored -> throw new IdempotencyKeyReusedException(key);
        };
    }

    private static void checkArguments(OwnerId owner, List<CalendarEvent> events) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        // 這是呼叫端違約（契約說不可為 null），不是業務規則，所以在這裡擋、而不是在 model。
        // 丟 IllegalArgumentException 而不是 NullPointerException：跟其他「輸入不合規」同一種，HTTP 那頭對應 400
        // 不用 events.contains(null)：List.of 做出來的不可變清單，contains(null) 會直接丟 NullPointerException
        if (events == null || events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("行程清單和裡面的每一筆都不可為 null");
        }
    }
}
