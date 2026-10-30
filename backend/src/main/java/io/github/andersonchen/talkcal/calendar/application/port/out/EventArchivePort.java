package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.time.YearMonth;
import java.util.List;

/**
 * 行程的歸檔（outbound port，#54）：由核心定義、由 Adapter（S3 相容的物件儲存…）實作。
 *
 * core 只知道「一個人、一個月的行程，存一包；之後讀得回來」。壓成什麼格式、key 怎麼排、放在哪個 bucket，是 adapter 的事。
 *
 * 約定：
 * - store：month 是行程「結束」的那個月。正常回傳 = 已經確實存好（物件儲存回了成功），呼叫端才可以刪資料庫裡的那幾筆。
 *   存不進去丟 IllegalStateException —— 絕不能「沒存好卻正常回傳」，不然資料庫那份一刪就真的沒了
 * - store 同一個人、同一個月可以呼叫很多次（每天的排程各存一包），load 時全部讀出來
 * - load：months 這幾個月（兩端都含）裡，owner 的全部行程。同一個 id 出現好幾次（重跑、還原後又歸檔）只回一筆
 * - load 只讀得到 owner 的：別人的那幾包不會出現，也不會混進來
 * - deleteAll：刪掉 owner 在歸檔裡的全部資料，回傳刪了幾包。給刪除帳號（#52）用，歸檔不能變成刪不掉的備份
 * - 失敗時丟 IllegalStateException
 */
public interface EventArchivePort {

    void store(OwnerId owner, YearMonth month, List<ScheduledEvent> events);

    List<ScheduledEvent> load(OwnerId owner, MonthRange months);

    int deleteAll(OwnerId owner);
}
