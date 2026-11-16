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
 * 每個依賴都一樣、而 Resilience4j starter 沒做的兩件事：斷路器狀態改變時留 log、數 bulkhead 擋掉幾個。
 * 斷路器和 bulkhead 本身由 starter 照 application.properties 的 resilience4j.* 建；怎麼包住模型見 GuardedChatModel。
 *
 * 用 RegistryEventConsumer：registry 每建一個就套上，之後新增的依賴不必另外設定。
 *
 * 要保護的地方明寫 decorator，不用 @CircuitBreaker 註解：多一層 AOP 代理，出問題時比較難追。
 */
@Configuration(proxyBeanMethods = false)
class ResilienceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfiguration.class);

    // Loki 上查得到哪個依賴幾點打開、幾點關回來，跟指標的時間軸對得起來
    @Bean
    RegistryEventConsumer<CircuitBreaker> logCircuitBreakerTransitions() {
        return new OnAdded<>(breaker -> breaker.getEventPublisher().onStateTransition(event ->
                log.warn("斷路器狀態改變：name={} {}", event.getCircuitBreakerName(), event.getStateTransition())));
    }

    // starter 註冊的 bulkhead 指標只有「剩幾個位子」，沒有「擋掉幾個」
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

    /** 只處理新建：設定在啟動時就定了，執行中不會換掉或移除。 */
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
