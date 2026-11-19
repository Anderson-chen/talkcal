package io.github.andersonchen.talkcal;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * 整合測試共用一個 Redis 容器，每個測試 class 用不同的 database 編號，計數不會互相干擾。做法和理由見 TestPostgres。
 *
 * 映像檔跟 deploy/compose.yaml 的 redis 用同一個版本，改的時候一起改。
 * Redis 只有 16 個 database（0～15），超過就從頭輪：輪到同一個編號的 class 不會同時執行（Gradle 一個 JVM 照順序跑）。
 */
public final class TestRedis {

    public static final String IMAGE = "redis:8.2.2-alpine";

    private static final AtomicInteger COUNT = new AtomicInteger();

    private static GenericContainer<?> server;
    private static RuntimeException startFailure;

    private TestRedis() {
    }

    /** 把這個 class 的 spring.data.redis.* 指到共用容器裡的一個 database，回傳它的編號 */
    public static int register(DynamicPropertyRegistry registry) {
        GenericContainer<?> redis = server();
        int database = COUNT.getAndIncrement() % 16;
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.database", () -> database);
        return database;
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
