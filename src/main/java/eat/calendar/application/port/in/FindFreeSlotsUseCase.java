package eat.calendar.application.port.in;

import eat.calendar.application.domain.model.DayPeriod;
import eat.calendar.application.domain.model.FreeSlot;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 找空檔（inbound port）：某幾天的某個時段裡，哪些時間沒有行程、而且夠長。
 *
 * 期間的規則跟 ListEventsUseCase 一樣（start 含、endExclusive 不含、最多 100 天），已經過去的時間不算。
 *
 * 失敗時：
 * - 期間不合規、最短長度不是正的 → IllegalArgumentException
 * - 資料庫出事 → IllegalStateException
 */
public interface FindFreeSlotsUseCase {

    List<FreeSlot> findFreeSlots(LocalDate start, LocalDate endExclusive, DayPeriod period, Duration minimum);
}
