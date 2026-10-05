package eat.calendar.application.domain.service;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.port.in.AddEventsUseCase;
import eat.calendar.application.port.out.SaveEventsPort;

import java.util.List;
import java.util.Objects;

/**
 * 實作「把確認過的行程排進行事曆」。
 * 約定：service 只呼叫 model 的方法與 port，不自己判斷業務規則。
 *
 * 流程只有兩步：每筆排進去（發 id）→ 一次存起來。
 * 行程本身的規則已經在 CalendarEvent 建構時檢查過，這裡不再看第二次。
 */
public final class AddEventsService implements AddEventsUseCase {

    private final SaveEventsPort saveEventsPort;

    public AddEventsService(SaveEventsPort saveEventsPort) {
        this.saveEventsPort = Objects.requireNonNull(saveEventsPort, "saveEventsPort 不可為 null");
    }

    @Override
    public List<ScheduledEvent> addEvents(List<CalendarEvent> events) {
        // 這是呼叫端違約（契約說不可為 null），不是業務規則，所以在這裡擋、而不是在 model。
        // 丟 IllegalArgumentException 而不是 NullPointerException：跟其他「輸入不合規」同一種，HTTP 那頭對應 400
        // 不用 events.contains(null)：List.of 做出來的不可變清單，contains(null) 會直接丟 NullPointerException
        if (events == null || events.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("行程清單和裡面的每一筆都不可為 null");
        }
        if (events.isEmpty()) {
            // 沒東西要存就不驚動資料庫
            return List.of();
        }
        List<ScheduledEvent> scheduled = events.stream().map(ScheduledEvent::schedule).toList();
        saveEventsPort.save(scheduled);
        return scheduled;
    }
}
