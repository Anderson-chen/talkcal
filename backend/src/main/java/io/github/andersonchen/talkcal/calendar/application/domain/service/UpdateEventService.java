package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventChangedException;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.in.UpdateEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.UpdateEventPort;

import java.util.Objects;

/**
 * 實作「把一個行程改成另一個樣子」。
 *
 * 防止兩邊同時改用「改之前看到的樣子」比對，不加 version 欄位：一筆行程的全部內容就是那幾個欄位，
 * 欄位全都一樣就是同一個版本。改過又改回原樣的那筆會被當成沒改過，但內容本來就一樣，蓋過去不會丟掉任何改動。
 * 這樣不必改表、GET 的回應也不必多帶一個版本號。
 * 代價：calendar_event 之後加欄位，要記得加進 JdbcCalendarEventAdapter 的比對（SAME_CONTENT）和前端送的 expected，
 * 漏了那個欄位就沒有並行保護。欄位多到容易漏的時候，改成 version 欄位。
 *
 * 一句 UPDATE 同時比對和修改；改不到才回頭查一次，分出「不存在」（404）跟「被改過」（409）。
 */
public final class UpdateEventService implements UpdateEventUseCase {

    private final UpdateEventPort updateEventPort;
    private final LoadEventPort loadEventPort;

    public UpdateEventService(UpdateEventPort updateEventPort, LoadEventPort loadEventPort) {
        this.updateEventPort = Objects.requireNonNull(updateEventPort, "updateEventPort 不可為 null");
        this.loadEventPort = Objects.requireNonNull(loadEventPort, "loadEventPort 不可為 null");
    }

    @Override
    public ScheduledEvent updateEvent(OwnerId owner, EventId id, CalendarEvent expected, CalendarEvent replacement) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        if (id == null) {
            throw new IllegalArgumentException("行程 ID 不可為 null");
        }
        if (expected == null || replacement == null) {
            throw new IllegalArgumentException("改之前和改之後的行程都不可為 null");
        }
        if (updateEventPort.update(owner, new ScheduledEvent(id, expected), replacement)) {
            return new ScheduledEvent(id, replacement);
        }
        if (loadEventPort.loadOne(owner, id).isEmpty()) {
            throw new EventNotFoundException(id);
        }
        throw new EventChangedException(id);
    }
}
