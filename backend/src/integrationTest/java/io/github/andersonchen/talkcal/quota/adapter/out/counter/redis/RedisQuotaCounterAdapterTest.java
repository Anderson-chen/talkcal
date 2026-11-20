package io.github.andersonchen.talkcal.quota.adapter.out.counter.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.andersonchen.talkcal.TestRedis;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaBucket;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRule;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRules;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaSubject;
import io.github.andersonchen.talkcal.quota.application.domain.service.ConsumeQuotaService;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase.QuotaRequest;
import io.github.andersonchen.talkcal.quota.application.port.in.QuotaExceededException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * 自己開一個 Redis 容器，設定跟 deploy/compose.yaml 一樣開 AOF（appendfsync everysec）：測試要重啟這個容器，所以不能用共用的那個。
 * 兩個 adapter 各用自己的連線，就跟兩個 app 執行個體連同一個 Redis 一樣。
 */
@DisplayName("RedisQuotaCounterAdapter（真的 Redis）")
class RedisQuotaCounterAdapterTest {

    // DEBUG RESTART：讓 Redis 在容器裡重新啟動、從 AOF 讀回資料，容器本身和對外的埠不變
    static final GenericContainer<?> REDIS = new GenericContainer<>(TestRedis.IMAGE)
            .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "everysec", "--enable-debug-command", "yes")
            .withExposedPorts(6379);

    static LettuceConnectionFactory appOne;
    static LettuceConnectionFactory appTwo;
    static StringRedisTemplate redis;

    private static final Instant TOMORROW = Instant.now().plus(Duration.ofDays(1));

    @BeforeAll
    static void start() {
        REDIS.start();
        appOne = connect();
        appTwo = connect();
        redis = new StringRedisTemplate(appOne);
    }

    @AfterAll
    static void stop() {
        appOne.destroy();
        appTwo.destroy();
        REDIS.stop();
    }

    private static LettuceConnectionFactory connect() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @BeforeEach
    void clean() {
        TestRedis.flush(redis);
    }

    @Test
    @DisplayName("加一回傳加完的值，第一次就設好到期時間；退回會減一")
    void incrementsAndExpires() {
        RedisQuotaCounterAdapter counter = new RedisQuotaCounterAdapter(new StringRedisTemplate(appOne));
        assertEquals(1, counter.increment("k", TOMORROW));
        assertEquals(2, counter.increment("k", TOMORROW));
        Long ttl = redis.getExpire("k");
        assertTrue(ttl != null && ttl > 0 && ttl <= Duration.ofDays(1).toSeconds(), "到期時間：" + ttl);

        counter.decrement("k");
        assertEquals("1", redis.opsForValue().get("k"));
    }

    @Test
    @DisplayName("反例：key 已經過期才退回，不會留下一個 -1、沒有到期時間的 key")
    void refundAfterExpiryLeavesNothing() {
        new RedisQuotaCounterAdapter(new StringRedisTemplate(appOne)).decrement("gone");
        assertEquals(Boolean.FALSE, redis.hasKey("gone"));
    }

    @Test
    @DisplayName("兩個 app 執行個體同時各加 50 次，合計剛好 100，不會漏算")
    void twoInstancesShareOneCount() throws Exception {
        RedisQuotaCounterAdapter one = new RedisQuotaCounterAdapter(new StringRedisTemplate(appOne));
        RedisQuotaCounterAdapter two = new RedisQuotaCounterAdapter(new StringRedisTemplate(appTwo));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                RedisQuotaCounterAdapter instance = i % 2 == 0 ? one : two;
                done.add(pool.submit(() -> {
                    go.await();
                    return instance.increment("shared", TOMORROW);
                }));
            }
            go.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("100", redis.opsForValue().get("shared"));
    }

    @Test
    @DisplayName("兩個 app 執行個體各自算每日次數、連同一個 Redis：訪客問助理兩邊合計第 4 次被擋，之後兩邊都擋")
    void twoInstancesShareTheDailyLimit() {
        // 跟 V15 的 guest-assistant 一樣：同一個 IP 每天 3 次
        QuotaRules rules = QuotaRules.of(
                List.of(new QuotaRule("POST", "/api/calendar/assistant", "guest", "guest-assistant")),
                List.of(new QuotaBucket("guest-assistant", QuotaSubject.IP, 3, "今天訪客問助理的次數用完了")));
        Clock clock = Clock.system(ZoneId.of("Asia/Taipei"));
        ConsumeQuotaService one = new ConsumeQuotaService(() -> rules,
                new RedisQuotaCounterAdapter(new StringRedisTemplate(appOne)), clock, Duration.ofMinutes(1));
        ConsumeQuotaService two = new ConsumeQuotaService(() -> rules,
                new RedisQuotaCounterAdapter(new StringRedisTemplate(appTwo)), clock, Duration.ofMinutes(1));
        QuotaRequest ask = new QuotaRequest("POST", "/api/calendar/assistant", "guest", "203.0.113.7", "guest-account");

        assertTrue(one.consume(ask).isPresent());
        assertTrue(two.consume(ask).isPresent());
        assertTrue(one.consume(ask).isPresent());

        assertThrows(QuotaExceededException.class, () -> two.consume(ask));
        assertThrows(QuotaExceededException.class, () -> one.consume(ask));
    }

    @Test
    @DisplayName("Redis 重啟之後，當天的次數還在（AOF）")
    void survivesRestart() throws Exception {
        RedisQuotaCounterAdapter counter = new RedisQuotaCounterAdapter(new StringRedisTemplate(appOne));
        counter.increment("today", TOMORROW);
        counter.increment("today", TOMORROW);
        // everysec：最多等一秒才寫進磁碟
        Thread.sleep(1500);

        REDIS.execInContainer("redis-cli", "DEBUG", "RESTART");

        long after = 0;
        for (int i = 0; i < 50 && after == 0; i++) {
            try {
                after = counter.increment("today", TOMORROW);
            } catch (IllegalStateException e) {
                Thread.sleep(200);
            }
        }
        assertEquals(3, after);
    }
}
