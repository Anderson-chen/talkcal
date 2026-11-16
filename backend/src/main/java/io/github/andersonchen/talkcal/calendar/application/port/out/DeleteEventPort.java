package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 回傳有沒有刪到，不自己丟「找不到」：找不到算不算錯是 use case 的判斷。
 * id 對、擁有者不對，就是沒刪到（回 false）。失敗時（資料庫出錯）丟 IllegalStateException。
 */
public interface DeleteEventPort {

    boolean delete(OwnerId owner, EventId id);
}
