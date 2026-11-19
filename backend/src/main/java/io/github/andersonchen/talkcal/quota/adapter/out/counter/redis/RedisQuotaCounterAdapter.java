package io.github.andersonchen.talkcal.quota.adapter.out.counter.redis;

import io.github.andersonchen.talkcal.quota.application.port.out.QuotaCounterPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 計數放 Redis，不放資料庫：每個受限的請求都要加一，放資料庫等於每個請求多一次寫入。
 * Redis 開了 AOF（deploy/compose.yaml），重啟後最多少掉約 1 秒的計數。多個 app 副本連同一個 Redis，看到的是同一份。
 */
public final class RedisQuotaCounterAdapter implements QuotaCounterPort {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaCounterAdapter.class);

    // 加一和設到期放在同一段 script：Redis 一次只執行一段 script，不會有「加了一但還沒設到期」時 app 掛掉、key 永遠不消失
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIREAT', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    // 只在 key 還在時減：key 已經過期（跨過午夜才退）的話，減了會留下一個 -1、沒有到期時間的 key
    private static final RedisScript<Long> DECREMENT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('DECR', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisQuotaCounterAdapter(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis 不可為 null");
    }

    @Override
    public long increment(String key, Instant expiresAt) {
        try {
            Long used = redis.execute(INCREMENT, List.of(key), Long.toString(expiresAt.getEpochSecond()));
            return Objects.requireNonNull(used, "INCR 沒有回傳值");
        } catch (DataAccessException e) {
            log.warn("每日次數的計數暫時無法使用，這個請求不限制：key={}", key, e);
            throw new IllegalStateException("每日次數的計數暫時無法使用", e);
        }
    }

    @Override
    public void decrement(String key) {
        try {
            redis.execute(DECREMENT, List.of(key));
        } catch (DataAccessException e) {
            log.warn("每日次數退不回去：key={}", key, e);
            throw new IllegalStateException("每日次數退不回去", e);
        }
    }
}
