package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 一個擁有者名下的全部資料：行程、對話、提議卡片。訪客登入正式帳號時搬過去，訪客太久沒用時刪掉。
 * 失敗丟 IllegalStateException，兩個方法都是全部成功或全部不算。
 */
public interface OwnedDataUseCase {

    /** from 名下的全部改到 to 名下；from、to 一樣時什麼都不做 */
    void transfer(OwnerId from, OwnerId to);

    void deleteAll(OwnerId owner);
}
