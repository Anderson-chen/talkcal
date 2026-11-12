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
 * 叫模型時的保護（#44）：斷路器、bulkhead、每次呼叫的逾時，見 GuardedChatModel。
 * 要叫模型的 adapter 都用 @Qualifier(GUARDED_CHAT_MODEL) 注入這一個。
 */
@Configuration(proxyBeanMethods = false)
class ChatModelConfiguration {

    static final String GUARDED_CHAT_MODEL = "guardedChatModel";

    /** 模型那一組斷路器、bulkhead 的名字：application.properties 的 instances.chat-model、指標上 name 這個 tag 都是它。 */
    static final String CHAT_MODEL = "chat-model";

    /**
     * spring.ai.openai.chat.timeout 要再交一次：Spring AI 自動建的那個每次呼叫只等 60 秒，原因見 GuardedChatModel.getOptions。
     *
     * defaultCandidate = false：它也是 ChatModel，一般按型別要 ChatModel 的地方（例如 Spring AI 自己的 ChatClient.Builder）
     * 看不到它、照樣拿 Spring AI 自動建的那個；只有指名 @Qualifier(GUARDED_CHAT_MODEL) 的才拿得到。
     */
    @Bean(name = GUARDED_CHAT_MODEL, defaultCandidate = false)
    ChatModel guardedChatModel(ChatModel chatModel, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
                               @Value("${talkcal.model.busy-retry-after}") Duration busyRetryAfter,
                               @Value("${spring.ai.openai.chat.timeout}") Duration callTimeout) {
        return new GuardedChatModel(chatModel, breakers.circuitBreaker(CHAT_MODEL),
                bulkheads.bulkhead(CHAT_MODEL), busyRetryAfter, callTimeout);
    }
}
