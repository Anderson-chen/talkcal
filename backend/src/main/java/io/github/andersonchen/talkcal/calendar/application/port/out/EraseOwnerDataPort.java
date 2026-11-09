package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 刪掉一個擁有者名下的所有資料（outbound port）。
 *
 * 「所有」是實作的責任：存在哪幾張表只有實作知道，新增一張帶擁有者的表時，要記得加進實作裡 ——
 * PrivacySystemTest 會掃資料庫裡所有帶 owner_id 欄位的表，漏了就紅燈。
 *
 * 全部刪或全部不刪（一個交易）：刪一半的話，使用者以為刪了、其實還留著一部分。
 * 回傳刪了幾列。失敗時丟 IllegalStateException。
 */
public interface EraseOwnerDataPort {

    int eraseAll(OwnerId owner);
}
