package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 從行事曆拿掉一個行程（inbound port）。
 *
 * 收 EventId 而不是字串：ID 格式對不對是 EventId.of 的規則，呼叫端（HTTP controller）先轉好，
 * 格式錯的在那裡就是 400，不必走到這裡。
 *
 * 只刪得到 owner 自己的行程。拿別人行程的 id 來刪，跟 id 不存在是同一個結果（EventNotFoundException → 404）：
 * 回 403 等於告訴對方「這個 id 存在，只是不是你的」（#104）。
 *
 * 失敗時：
 * - owner 是 null、id 是 null → IllegalArgumentException
 * - owner 沒有這個行程（從來沒有、已經刪掉了、或是別人的）→ EventNotFoundException
 * - 資料庫出事 → IllegalStateException
 */
public interface RemoveEventUseCase {

    void removeEvent(OwnerId owner, EventId id);
}
