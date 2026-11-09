package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 找出該歸檔的行程（outbound port，#54）：結束時間早於 cutoff 的，不分擁有者，依擁有者分好。
 *
 * 不分擁有者：這是排程的維護工作，不是誰的請求。分好再交回來，是因為歸檔一定一個人一包（見 EventArchivePort）。
 *
 * 約定：
 * - 「結束早於 cutoff」= end < cutoff；剛好在 cutoff 結束的不算
 * - 一次最多 limit 筆：一次全部讀進記憶體，資料一多就撐爆。剩下的由呼叫端下一輪再拿
 * - 失敗時丟 IllegalStateException
 */
public interface LoadEndedEventsPort {

    Map<OwnerId, List<ScheduledEvent>> loadEndedBefore(LocalDateTime cutoff, int limit);
}
