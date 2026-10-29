package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;

/**
 * 記下產品用量（outbound port，#36）：功能有沒有人用、助理的提議有沒有用處。
 *
 * core 只說「發生了什麼業務事件」，記到哪（Micrometer → Prometheus、別的分析系統、或測試裡的計數器）是 adapter 的事 ——
 * 跟 SaveEventsPort 不知道背後是 PostgreSQL 同一個道理，ArchitectureTest 也不准 core 碰框架。
 *
 * 只收次數和分類，不收擁有者：指標的標籤放使用者 ID 會讓時間序列跟著人數長（基數爆炸），
 * 「多少人在用」另外由 CountActiveOwnersPort 從資料庫數。
 *
 * 記錄失敗不該讓業務失敗：實作要自己吞掉例外（目前的 Micrometer 本來就不會丟）。
 */
public interface RecordUsagePort {

    /** 助理這一輪給使用者看了幾張提議卡片。 */
    void proposalsShown(int count);

    /** 使用者對幾張提議卡片按了「取消」或「移除」。 */
    void proposalsDismissed(int count);

    /** 存進行事曆幾筆，從哪裡來的。origin = ASSISTANT 就是採納。 */
    void eventsAdded(EventOrigin origin, int count);
}
