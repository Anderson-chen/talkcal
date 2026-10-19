package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.YearMonth;

/**
 * 把歸檔裡的行程放回行事曆（inbound port，#54）：owner 的、結束在 from 到 to（含兩端）這幾個月的。
 *
 * 只讀得到、只放得回 owner 自己的：不收「誰的」以外的參數，別人的歸檔碰不到。
 * 已經在行事曆上的同一筆不會被蓋掉（見 RestoreEventsPort），按幾次結果都一樣。
 * 放回去之後，下一次排程又會把它歸檔 —— 還原是「要看的時候拿回來」，不是改掉保留期限。
 *
 * 失敗時：
 * - from 晚於 to、範圍超過 MAX_MONTHS 個月 → IllegalArgumentException
 * - 歸檔或資料庫出事 → IllegalStateException
 *
 * @return 實際放回去幾筆
 */
public interface RestoreEventsUseCase {

    // 一次最多還原幾個月：每個月要列一次物件儲存，範圍沒上限就是讓一個請求掃整個 bucket
    int MAX_MONTHS = 24;

    int restore(OwnerId owner, YearMonth from, YearMonth to);
}
