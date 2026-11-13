package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;
import io.github.andersonchen.talkcal.calendar.application.port.in.ParseEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.ExtractEventsPort;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 「現在」從建構子收的 Clock 取，不直接呼叫 LocalDateTime.now()：測試給固定時鐘，「明天」就是確定的日期；
 * 用哪個時區由組裝根決定，core 不知道。Clock 是 JDK 的型別，core 可以依賴。
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
        // 空白的描述在這裡就擋下，不會叫到模型
        EventDescription described = new EventDescription(description);
        // List.copyOf 會拒絕 null 元素：adapter 漏翻一筆的話在這裡就炸，不會一路傳到畫面上
        return List.copyOf(extractEventsPort.extractEvents(described, LocalDateTime.now(clock)));
    }
}
