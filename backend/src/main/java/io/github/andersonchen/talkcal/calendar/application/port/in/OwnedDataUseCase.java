package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 一個擁有者名下的全部資料：行程、對話、提議卡片，和行程的歸檔。訪客登入正式帳號時搬過去，訪客太久沒用時刪掉。
 * AI 助理對話的歸檔不在 core 裡，不歸這裡管（見 app.job.AssistantMemoryArchive）。
 *
 * 失敗丟 IllegalStateException。資料庫的部分全部成功或全部不算；歸檔是一包一包處理的，中途失敗時已經處理的留著，
 * 再呼叫一次會處理剩下的。
 */
public interface OwnedDataUseCase {

    /** from 名下的全部改到 to 名下，同一段對話兩邊都有時 from 的接在後面；from、to 一樣時什麼都不做 */
    void transfer(OwnerId from, OwnerId to);

    void deleteAll(OwnerId owner);
}
