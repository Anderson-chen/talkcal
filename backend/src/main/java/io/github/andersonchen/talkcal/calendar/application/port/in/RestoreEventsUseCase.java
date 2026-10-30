package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 把歸檔裡的行程放回行事曆（inbound port，#54）：owner 的、結束在 months 這幾個月（兩端都含）的。
 *
 * 只讀得到、只放得回 owner 自己的：不收「誰的」以外的參數，別人的歸檔碰不到。
 * 已經在行事曆上的同一筆不會被蓋掉（見 RestoreEventsPort），按幾次結果都一樣。
 * 放回去之後，下一次排程又會把它歸檔 —— 還原是「要看的時候拿回來」，不是改掉保留期限。
 *
 * 範圍合不合規（from 不晚於 to、最多幾個月）是 MonthRange 建構時檢查的，這裡收到的一定是合規的。
 *
 * 失敗時：
 * - owner、months 是 null → IllegalArgumentException
 * - 歸檔或資料庫出事 → IllegalStateException
 *
 * @return 實際放回去幾筆
 */
public interface RestoreEventsUseCase {

    int restore(OwnerId owner, MonthRange months);
}
