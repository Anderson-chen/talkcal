package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.calendar.adapter.shared.GuardedChatModel;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ChatModelConfiguration {

    static final String GUARDED_CHAT_MODEL = "guardedChatModel";

    /**
     * 要跟 application.properties 的 resilience4j.*.instances.chat-model 同名：對不上的話，starter 會默默用預設值另建一個。
     * 指標的 name tag 也是這個值，改名之後照 tag 篩的告警就查不到資料。
     */
    static final String CHAT_MODEL = "chat-model";

    /**
     * defaultCandidate = false：注入 ChatModel 時沒帶 @Qualifier(GUARDED_CHAT_MODEL)，拿到的是 Spring AI 自動組裝、
     * 沒有斷路器和 bulkhead 的那個，而且不會報錯。
     */
    @Bean(name = GUARDED_CHAT_MODEL, defaultCandidate = false)
    ChatModel guardedChatModel(ChatModel chatModel, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
                               @Value("${talkcal.model.busy-retry-after}") Duration busyRetryAfter,
                               @Value("${spring.ai.openai.chat.timeout}") Duration callTimeout) {
        return new GuardedChatModel(chatModel, breakers.circuitBreaker(CHAT_MODEL),
                bulkheads.bulkhead(CHAT_MODEL), busyRetryAfter, callTimeout);
    }
}
