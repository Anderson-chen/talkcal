package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * 照日期查看被排程搬到歸檔的行程，只讀，不放回行事曆。
 *
 * 收兩個 LocalDate（兩端都含）：範圍合不合規是 ArchivePeriod 的規則，呼叫端不必用到這個型別。
 * 跟範圍有重疊的就算，跨月的也算（例外見 ArchivePeriod.MONTHS_AFTER）。依開始時間排序，只讀得到 owner 自己的。
 *
 * 失敗時：
 * - owner 是 null、範圍不合規 → IllegalArgumentException
 * - 物件儲存出錯、或封存的資料不合規則 → IllegalStateException
 */
public interface ListArchivedEventsUseCase {

    List<ScheduledEvent> listArchivedEvents(OwnerId owner, LocalDate from, LocalDate to);
}
