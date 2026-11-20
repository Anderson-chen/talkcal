package io.github.andersonchen.talkcal;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * 整合測試共用一個 Redis 容器，每個測試 class 用不同的 database 編號，計數不會互相干擾。做法和理由見 TestPostgres。
 *
 * 映像檔跟 deploy/compose.yaml 的 redis 用同一個版本，改的時候一起改。
 * Redis 只有 16 個 database（0～15），超過就從頭輪：輪到同一個編號的 class 不會同時執行（Gradle 在同一個 JVM 裡照順序執行）。
 */
public final class TestRedis {

    public static final String IMAGE = "redis:8.10.3-alpine";

    private static final AtomicInteger COUNT = new AtomicInteger();

    private static GenericContainer<?> server;
    private static RuntimeException startFailure;

    private TestRedis() {
    }

    /** 把這個 class 的 spring.data.redis.* 指到共用容器裡的一個 database */
    public static void register(DynamicPropertyRegistry registry) {
        GenericContainer<?> redis = server();
        int database = COUNT.getAndIncrement() % 16;
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> database);
    }

    /** 清空 redis 連到的那個 database（每個測試開始前把計數歸零） */
    public static void flush(StringRedisTemplate redis) {
        redis.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
    }

    private static synchronized GenericContainer<?> server() {
        if (startFailure != null) {
            throw startFailure;
        }
        if (server == null) {
            GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(6379);
            try {
                container.start();
            } catch (RuntimeException e) {
                startFailure = e;
                throw e;
            }
            server = container;
        }
        return server;
    }
}
