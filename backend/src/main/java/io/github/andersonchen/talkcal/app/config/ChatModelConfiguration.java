package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.calendar.adapter.shared.GuardedChatModel;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 叫模型時的保護：斷路器、bulkhead、每次呼叫的逾時。要叫模型的 adapter 用 @Qualifier(GUARDED_CHAT_MODEL) 注入。
 */
@Configuration(proxyBeanMethods = false)
class ChatModelConfiguration {

    static final String GUARDED_CHAT_MODEL = "guardedChatModel";

    /**
     * 模型外面包一層 GuardedChatModel（#44），助理和抽行程都用這一個：背後是同一顆模型，壞就一起壞。
     * 規則在 application.properties 的 resilience4j.*.instances.chat-model。
     *
     * 每一次呼叫的逾時也是在這裡補上的（GuardedChatModel.getOptions）：Spring AI 自動建的那個沒包保護，每次呼叫只等 60 秒，
     * 之後有新的地方要叫模型，一樣用 @Qualifier(GUARDED_CHAT_MODEL) 拿這一個。
     *
     * defaultCandidate = false：它也是 ChatModel，一般按型別要 ChatModel 的地方（例如 Spring AI 自己的 ChatClient.Builder）
     * 看不到它、照樣拿 Spring AI 自動建的那個；只有指名 @Qualifier(GUARDED_CHAT_MODEL) 的才拿得到。
     */
    @Bean(name = GUARDED_CHAT_MODEL, defaultCandidate = false)
    ChatModel guardedChatModel(ChatModel chatModel, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
                               @Value("${talkcal.model.busy-retry-after}") Duration busyRetryAfter,
                               @Value("${spring.ai.openai.chat.timeout}") Duration callTimeout) {
        return new GuardedChatModel(chatModel, breakers.circuitBreaker(ResilienceConfiguration.CHAT_MODEL),
                bulkheads.bulkhead(ResilienceConfiguration.CHAT_MODEL), busyRetryAfter, callTimeout);
    }
}
