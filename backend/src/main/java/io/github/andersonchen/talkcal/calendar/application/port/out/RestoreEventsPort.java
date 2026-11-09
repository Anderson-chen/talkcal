package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 把歸檔裡讀回來的行程放回行事曆（outbound port，#54）。
 *
 * 跟 SaveEventsPort 不同的地方：同一個 id 已經在行事曆上（之前還原過、或根本還沒被刪）就跳過，不算錯。
 * 還原可以按很多次，結果都一樣；也不會拿歸檔裡的舊版本蓋掉線上那筆。
 *
 * 回傳實際放回去幾筆。全部放或全部不放。失敗時丟 IllegalStateException。
 */
public interface RestoreEventsPort {

    int restore(OwnerId owner, List<ScheduledEvent> events);
}
