package io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.andersonchen.talkcal.calendar.adapter.shared.ModelUnavailableException;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 叫模型失敗時丟什麼。正常抽行程要真模型，在 integrationTest 的 SpringAiExtractEventsAdapterTest；
 * 這裡只看失敗的兩條路，用丟例外的假模型就夠，不必起模型。
 */
@DisplayName("SpringAiExtractEventsAdapter 失敗時")
class SpringAiExtractEventsAdapterFailureTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 11, 30);

    private static SpringAiExtractEventsAdapter failingWith(RuntimeException failure) {
        return new SpringAiExtractEventsAdapter(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw failure;
            }
        });
    }

    @Test
    @DisplayName("#44 模型沒被叫（斷路器、bulkhead 擋下）：原樣丟出去，不包一層 —— 助理的工具框架靠這個型別認出它")
    void modelUnavailablePassesThrough() {
        ModelUnavailableException unavailable =
                new ModelUnavailableException(ModelUnavailableException.Reason.CIRCUIT_OPEN, Duration.ofSeconds(30), null);

        assertSame(unavailable, assertThrows(ModelUnavailableException.class,
                () -> failingWith(unavailable).extractEvents(new EventDescription("明天晚上七點吃飯"), NOW)));
    }

    @Test
    @DisplayName("反例：其他失敗（SDK 的各種例外）照 port 的契約包成 IllegalStateException，保留原因")
    void otherFailuresAreWrapped() {
        RuntimeException sdk = new RuntimeException("Connection refused");

        IllegalStateException wrapped = assertThrows(IllegalStateException.class,
                () -> failingWith(sdk).extractEvents(new EventDescription("明天晚上七點吃飯"), NOW));
        assertSame(sdk, wrapped.getCause());
        assertEquals(IllegalStateException.class, wrapped.getClass());
    }
}
