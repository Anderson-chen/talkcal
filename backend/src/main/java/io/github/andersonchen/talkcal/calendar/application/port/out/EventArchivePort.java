package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.time.YearMonth;
import java.util.List;

/**
 * core 只知道「一個人、一個月的行程存一包，之後讀得回來」；壓成什麼格式、key 怎麼排、放哪個 bucket 是 adapter 的事。
 *
 * 約定：
 * - store：month 是行程結束的那個月。正常回傳就是物件儲存確實回了成功，呼叫端才可以刪資料庫裡的那幾筆；
 *   存不進去一定要丟 IllegalStateException，沒存好卻正常回傳的話，資料庫那份一刪就真的沒了
 * - store 同一個人、同一個月可以呼叫很多次（每天的排程各存一包），load 時全部讀出來
 * - load：結束在 firstMonth～lastMonth（兩端都含）的、owner 的全部行程，順序不保證；同一個 id 出現好幾次只回一筆
 * - load 只讀得到 owner 的，別人的那幾包不會混進來
 * - deleteAll：刪掉 owner 在歸檔裡的全部資料，回傳刪了幾包。刪帳號時歸檔也要跟著刪，不能變成刪不掉的備份
 * - 失敗時丟 IllegalStateException
 */
public interface EventArchivePort {

    void store(OwnerId owner, YearMonth month, List<ScheduledEvent> events);

    List<ScheduledEvent> load(OwnerId owner, YearMonth firstMonth, YearMonth lastMonth);

    int deleteAll(OwnerId owner);
}
