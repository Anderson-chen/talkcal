package eat.calendar.application.port.in;

import eat.calendar.application.domain.model.EventId;

/**
 * 從行事曆拿掉一個行程（inbound port）。
 *
 * 收 EventId 而不是字串：ID 格式對不對是 EventId.of 的規則，呼叫端（HTTP controller）先轉好，
 * 格式錯的在那裡就是 400，不必走到這裡。
 *
 * 失敗時：
 * - 沒有這個行程（從來沒有、或已經刪掉了）→ EventNotFoundException
 * - 資料庫出事 → IllegalStateException
 */
public interface RemoveEventUseCase {

    void removeEvent(EventId id);
}
