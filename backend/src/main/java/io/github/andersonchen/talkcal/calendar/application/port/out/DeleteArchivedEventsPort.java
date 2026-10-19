package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 刪掉已經歸檔好的行程（outbound port，#54）。只在 EventArchivePort.store 成功之後呼叫。
 *
 * 約定：
 * - 只刪 owner 的、ids 裡的，而且「現在」結束時間還早於 cutoff 的那幾筆。
 *   最後這個條件擋的是：讀出來、上傳的那幾秒之間，使用者把行程改到未來 —— 那筆已經不舊了，不能刪
 * - 回傳實際刪了幾筆
 * - 失敗時丟 IllegalStateException
 */
public interface DeleteArchivedEventsPort {

    int deleteArchived(OwnerId owner, Collection<EventId> ids, LocalDateTime cutoff);
}
