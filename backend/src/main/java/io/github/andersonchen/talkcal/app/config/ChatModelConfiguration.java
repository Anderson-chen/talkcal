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
     * 斷路器和 bulkhead 的名字，要跟 application.properties 的 resilience4j.*.instances.chat-model 同名，對不上會怎樣見那一段的開頭。
     * 指標上的 name tag 也是它：改了名字，照這個 tag 篩的告警會查不到資料。
     */
    static final String CHAT_MODEL = "chat-model";

    /**
     * defaultCandidate = false：不帶 @Qualifier(GUARDED_CHAT_MODEL) 注入 ChatModel 時，拿到的是 Spring AI 自動組裝的那個，
     * 沒有保護，也不會報錯。
     */
    @Bean(name = GUARDED_CHAT_MODEL, defaultCandidate = false)
    ChatModel guardedChatModel(ChatModel chatModel, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
                               @Value("${talkcal.model.busy-retry-after}") Duration busyRetryAfter,
                               @Value("${spring.ai.openai.chat.timeout}") Duration callTimeout) {
        return new GuardedChatModel(chatModel, breakers.circuitBreaker(CHAT_MODEL),
                bulkheads.bulkhead(CHAT_MODEL), busyRetryAfter, callTimeout);
    }
}
