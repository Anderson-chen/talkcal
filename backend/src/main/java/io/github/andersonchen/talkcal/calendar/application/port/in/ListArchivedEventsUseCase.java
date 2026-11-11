package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * 查看已經封存的行程（inbound port，#274）：結束超過保留期限、被排程搬到歸檔的行程，照日期只讀查看，不放回行事曆。
 *
 * 收兩個 LocalDate（from、to 都含，看一天就是同一天）：範圍合不合規（不倒過來、最多 24 個月）是 ArchivePeriod 的規則，
 * 跟 ListEventsUseCase 交給 DateRange 一樣，呼叫端不必先知道有這個型別。
 *
 * 「在範圍裡」跟月曆同一個定義：跟範圍有重疊就算，跨月的行程也算（例外見 ArchivePeriod.MONTHS_AFTER）。
 * 依開始時間排序。只讀得到 owner 自己的封存（#104）。不寫資料庫。
 *
 * 失敗時：
 * - owner 是 null、範圍不合規 → IllegalArgumentException
 * - 物件儲存出事、或封存的資料不合規則 → IllegalStateException
 */
public interface ListArchivedEventsUseCase {

    List<ScheduledEvent> listArchivedEvents(OwnerId owner, LocalDate from, LocalDate to);
}
