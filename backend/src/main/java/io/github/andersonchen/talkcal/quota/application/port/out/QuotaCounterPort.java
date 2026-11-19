package io.github.andersonchen.talkcal.quota.application.port.out;

import java.time.Instant;

/**
 * 每日計數。多個 app 副本要讀寫同一份，加一要是原子的：同時送來的請求不會一起看到「還沒到上限」。
 * 無法連線時丟 IllegalStateException。
 */
public interface QuotaCounterPort {

    /** 加一並回傳加完的值；這份計數第一次出現時設成在 expiresAt 消失 */
    long increment(String key, Instant expiresAt);

    void decrement(String key);
}
