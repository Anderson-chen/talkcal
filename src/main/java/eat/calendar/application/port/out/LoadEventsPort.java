package eat.calendar.application.port.out;

import eat.calendar.application.domain.model.DateRange;
import eat.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 讀出跟某段期間有重疊的行程（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 *
 * 約定：
 * - 「有重疊」= 行程開始 < 期間結束，而且行程結束 > 期間開始。
 *   剛好在期間開始那一刻結束的行程不算（它在前一段），剛好在期間結束那一刻開始的也不算（它在下一段）
 * - 依開始時間排序；開始時間相同時順序固定（不會每次查都不一樣）
 * - 失敗時丟 IllegalStateException
 */
public interface LoadEventsPort {

    List<ScheduledEvent> load(DateRange range);
}
