package eat.calendar.application.port.out;

import eat.calendar.application.domain.model.EventId;

/**
 * 刪除一個已存的行程（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 *
 * 回傳有沒有真的刪到東西，而不是自己丟「找不到」：
 * 「找不到算不算錯」是 use case 的判斷，adapter 只負責誠實回報資料庫發生了什麼。
 * 失敗時（資料庫出事）丟 IllegalStateException。
 */
public interface DeleteEventPort {

    boolean delete(EventId id);
}
