package eat.calendar.application.port.in;

import eat.calendar.application.domain.model.ScheduledEvent;

import java.time.LocalDate;
import java.util.List;

/**
 * 列出一段期間裡的行程（inbound port）：給月曆畫一頁用。
 *
 * 收兩個 LocalDate 而不是 DateRange：期間合不合規（結束晚於開始、不超過上限）是 model 的規則，
 * 由 service 交給 DateRange 檢查，呼叫端不必先知道有這個型別。
 *
 * 「在期間裡」指的是跟期間有重疊：跨夜、跨月的行程，前後兩段期間都看得到它。
 * 依開始時間排序。
 *
 * 失敗時：
 * - 期間不合規 → IllegalArgumentException
 * - 資料庫出事、或資料庫裡有不合規則的行程 → IllegalStateException
 */
public interface ListEventsUseCase {

    List<ScheduledEvent> listEvents(LocalDate start, LocalDate endExclusive);
}
