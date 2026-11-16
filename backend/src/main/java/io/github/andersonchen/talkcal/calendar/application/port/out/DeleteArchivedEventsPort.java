package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 只在 EventArchivePort.store 成功之後呼叫。
 *
 * 約定：
 * - 只刪 owner 的、而且資料庫裡現在跟 archived 那一份一模一樣（標題、起訖、分類、地點、備註）的那幾筆。
 *   擋的是讀出來到上傳完的那幾秒之間，使用者改了這筆行程：改成未來的，行程已經不舊了；
 *   改了備註之類的，歸檔裡存的是改之前的那份。兩種都不能刪，留到下次排程存新版
 * - 回傳實際刪了幾筆
 * - 失敗時丟 IllegalStateException
 */
public interface DeleteArchivedEventsPort {

    int deleteArchived(OwnerId owner, List<ScheduledEvent> archived);
}
