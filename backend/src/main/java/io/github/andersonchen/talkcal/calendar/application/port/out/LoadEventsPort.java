package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 約定：
 * - 只讀 owner 的行程
 * - 跟期間有重疊：行程開始 < 期間結束，而且行程結束 > 期間開始。
 *   剛好在期間開始那一刻結束的不算（它在前一段），剛好在期間結束那一刻開始的也不算（它在下一段）
 * - 依開始時間排序；開始時間相同時順序也固定，不會每次查都不一樣
 * - 失敗時丟 IllegalStateException
 */
public interface LoadEventsPort {

    List<ScheduledEvent> load(OwnerId owner, DateRange range);
}
