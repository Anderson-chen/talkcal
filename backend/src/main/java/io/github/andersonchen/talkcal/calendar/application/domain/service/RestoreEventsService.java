package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.RestoreEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RestoreEventsPort;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * 實作「把歸檔裡的行程放回行事曆」（#54）：讀那幾個月的包，放回資料庫。
 *
 * 歸檔裡的那一份不刪：還原是「要看的時候拿回來」，歸檔還是那份冷的備份。
 * 放回來的行程結束時間還是很舊，下次排程又會被歸檔一次 —— 歸檔裡多一包重複的，load 依 id 去重。
 */
public final class RestoreEventsService implements RestoreEventsUseCase {

    private final EventArchivePort archive;
    private final RestoreEventsPort restoreEvents;

    public RestoreEventsService(EventArchivePort archive, RestoreEventsPort restoreEvents) {
        this.archive = Objects.requireNonNull(archive, "archive 不可為 null");
        this.restoreEvents = Objects.requireNonNull(restoreEvents, "restoreEvents 不可為 null");
    }

    @Override
    public int restore(OwnerId owner, YearMonth from, YearMonth to) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("還原的起訖月份不可為 null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("還原的起始月份（" + from + "）不可晚於結束月份（" + to + "）");
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAX_MONTHS) {
            throw new IllegalArgumentException("一次最多還原 " + MAX_MONTHS + " 個月：" + from + " ~ " + to);
        }
        List<ScheduledEvent> events = archive.load(owner, from, to);
        return events.isEmpty() ? 0 : restoreEvents.restore(owner, events);
    }
}
