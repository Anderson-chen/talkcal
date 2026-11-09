package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 刪掉已經歸檔好的行程（outbound port，#54）。只在 EventArchivePort.store 成功之後呼叫。
 *
 * 約定：
 * - 只刪 owner 的、而且資料庫裡「現在」跟 archived 裡那一份一模一樣（標題、起訖、分類、地點、備註）的那幾筆。
 *   這個條件擋的是：讀出來、上傳的那幾秒之間，使用者改了這筆行程 ——
 *   改成未來的，它已經不舊了；改了備註之類的，歸檔裡存的是改之前的那份。兩種都不能刪，留到下次排程存新版
 * - 回傳實際刪了幾筆
 * - 失敗時丟 IllegalStateException
 */
public interface DeleteArchivedEventsPort {

    int deleteArchived(OwnerId owner, List<ScheduledEvent> archived);
}
