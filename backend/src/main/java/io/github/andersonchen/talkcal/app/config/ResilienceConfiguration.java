package io.github.andersonchen.talkcal.app.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 依賴的斷路器、bulkhead 的共用規則（#44）。怎麼包住模型見 GuardedChatModel。
 *
 * 斷路器和 bulkhead 本身由 Resilience4j 的 starter 照 application.properties 的 resilience4j.* 建：
 * 規則全部集中在那一段（configs.default 是所有依賴共用的預設，instances.<名字> 只寫不一樣的地方），
 * 多一個依賴只要加設定、在它的 adapter 依名字取出來用，不必再寫一套組裝和指標。
 *
 * 只用 starter 建好的 registry，不用 @CircuitBreaker 註解：要保護的地方明寫一個 decorator，
 * 比在方法上掛註解、多一層 AOP 代理好追。
 *
 * 這裡只補 starter 沒有、而且每個依賴都一樣的兩件事。用 RegistryEventConsumer：registry 每建一個就套上去，
 * 之後多的依賴自動有，不必一個一個接。
 */
@Configuration(proxyBeanMethods = false)
class ResilienceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfiguration.class);

    /** 模型那一組的名字：application.properties 的 instances.chat-model、指標上 name 這個 tag 都是它。 */
    static final String CHAT_MODEL = "chat-model";

    // 狀態一變就留一行 log：Loki 上查得到「哪個依賴幾點打開、幾點關回來」，跟指標的時間軸對得起來
    @Bean
    RegistryEventConsumer<CircuitBreaker> logCircuitBreakerTransitions() {
        return new OnAdded<>(breaker -> breaker.getEventPublisher().onStateTransition(event ->
                log.warn("斷路器狀態改變：name={} {}", event.getCircuitBreakerName(), event.getStateTransition())));
    }

    // starter 接上的 bulkhead 指標只有「剩幾個位子」，沒有「擋掉幾個」，自己數
    @Bean
    RegistryEventConsumer<Bulkhead> countBulkheadRejections(MeterRegistry meters) {
        return new OnAdded<>(bulkhead -> {
            Counter rejected = Counter.builder("talkcal.bulkhead.rejected")
                    .description("同時在等這個依賴的請求已滿，被 bulkhead 直接拒絕的次數")
                    .tag("name", bulkhead.getName())
                    .register(meters);
            bulkhead.getEventPublisher().onCallRejected(event -> rejected.increment());
        });
    }

    /** 只在意「新建了一個」；設定在啟動時就定了，執行中不會換掉或移除。 */
    private record OnAdded<E>(java.util.function.Consumer<E> action) implements RegistryEventConsumer<E> {

        @Override
        public void onEntryAddedEvent(EntryAddedEvent<E> event) {
            action.accept(event.getAddedEntry());
        }

        @Override
        public void onEntryRemovedEvent(EntryRemovedEvent<E> event) {
        }

        @Override
        public void onEntryReplacedEvent(EntryReplacedEvent<E> event) {
        }
    }
}
