package eat.calendar.application.domain.service;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.EventDescription;
import eat.calendar.application.port.in.ParseEventsUseCase;
import eat.calendar.application.port.out.ExtractEventsPort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 實作「把自然語言解析成行程」。
 * 約定跟 conversation 一樣：service 只呼叫 model 的方法與 port，不自己判斷業務規則。
 *
 * 時鐘從建構子收 java.time.Clock，而不是直接呼叫 LocalDateTime.now()：
 * Clock 是 JDK 的型別（不是 Spring 的），core 可以放心依賴；
 * 測試給固定時鐘，「明天」就是一個確定的日期。用哪個時區（台北）由組裝根決定，core 不知道。
 */
public final class ParseEventsService implements ParseEventsUseCase {

    private final ExtractEventsPort extractEventsPort;
    private final Clock clock;

    public ParseEventsService(ExtractEventsPort extractEventsPort, Clock clock) {
        this.extractEventsPort = Objects.requireNonNull(extractEventsPort, "extractEventsPort 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    @Override
    public List<CalendarEvent> parseEvents(String description) {
        // 先交給 model 檢查：空白的描述連模型都不會驚動
        EventDescription described = new EventDescription(description);
        // 複製成不可變的 List：adapter 回傳什麼實作都一樣，呼叫端拿到的永遠改不動。
        // List.copyOf 也會拒絕 null 元素 —— adapter 漏翻一筆的話在這裡就炸，不會一路傳到畫面上
        return List.copyOf(extractEventsPort.extractEvents(described, LocalDateTime.now(clock)));
    }
}
