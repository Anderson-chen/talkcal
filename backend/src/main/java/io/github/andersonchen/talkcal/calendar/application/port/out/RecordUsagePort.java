package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;

/**
 * 功能有沒有人用、助理的提議有沒有用處。core 只說發生了什麼業務事件，記到哪是 adapter 的事。
 *
 * 只收次數和分類，不收擁有者：指標的標籤放使用者 ID，時間序列會跟著人數長（基數爆炸）；
 * 多少人在用另外由 CountActiveOwnersPort 從資料庫數。
 *
 * 記錄失敗不該讓業務失敗，實作要自己吞掉例外。
 */
public interface RecordUsagePort {

    void proposalsShown(int count);

    /** 使用者按了「取消」或「移除」的卡片張數。 */
    void proposalsDismissed(int count);

    /** origin = ASSISTANT 就是採納。 */
    void eventsAdded(EventOrigin origin, int count);
}
