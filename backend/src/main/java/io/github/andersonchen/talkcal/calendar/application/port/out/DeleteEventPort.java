package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 刪除一個已存的行程（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 *
 * 回傳有沒有真的刪到東西，而不是自己丟「找不到」：
 * 「找不到算不算錯」是 use case 的判斷，adapter 只負責誠實回報資料庫發生了什麼。
 * 只刪 owner 的那一筆：id 對、擁有者不對，就是沒刪到（回 false），不是刪掉別人的。
 * 失敗時（資料庫出事）丟 IllegalStateException。
 */
public interface DeleteEventPort {

    boolean delete(OwnerId owner, EventId id);
}
