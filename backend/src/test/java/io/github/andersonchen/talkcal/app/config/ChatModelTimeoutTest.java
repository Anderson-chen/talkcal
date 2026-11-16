package io.github.andersonchen.talkcal.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * 為什麼逾時要 GuardedChatModel 自己補，見 GuardedChatModel.getOptions。
 * 助理和抽行程都從 GuardedChatModel 的 getOptions() 組選項，所以看 GuardedChatModel 的選項就是兩者實際等多久。
 *
 * 為什麼啟動整個 Spring 卻不需要資料庫，見 OpenApiDocumentationTest。@SpringBootTest、@AutoConfigureMockMvc 要跟 OpenApiDocumentationTest 一模一樣，Spring 才會重用同一個 context。
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
@DisplayName("叫模型的逾時")
class ChatModelTimeoutTest {

    // AbstractOpenAiOptions.DEFAULT_TIMEOUT
    private static final Duration SPRING_AI_DEFAULT = Duration.ofSeconds(60);

    @Autowired
    @Qualifier(ChatModelConfiguration.GUARDED_CHAT_MODEL)
    ChatModel guarded;

    // Spring AI 自動組裝、沒包 GuardedChatModel 的那個
    @Autowired
    ChatModel springAi;

    @Value("${spring.ai.openai.chat.timeout}")
    Duration configured;

    @Test
    @DisplayName("助理和抽行程每一次呼叫帶的逾時 = spring.ai.openai.chat.timeout，不是 Spring AI 預設的 60 秒")
    void perCallTimeoutIsTheConfiguredOne() {
        Duration perCall = ((OpenAiChatOptions) guarded.getOptions()).getTimeout();

        assertThat(perCall).isEqualTo(configured);
    }

    /**
     * 升級 Spring AI 後這條失敗了，代表 Spring AI 會自己帶設定的逾時，GuardedChatModel 補逾時那段就可以拿掉。
     */
    @Test
    @DisplayName("反例：Spring AI 自動組裝的選項，每一次呼叫是它預設的 60 秒（所以 GuardedChatModel 要補）")
    void springAiAloneUsesItsDefault() {
        assertThat(((OpenAiChatOptions) springAi.getOptions()).getTimeout()).isEqualTo(SPRING_AI_DEFAULT);
    }
}
