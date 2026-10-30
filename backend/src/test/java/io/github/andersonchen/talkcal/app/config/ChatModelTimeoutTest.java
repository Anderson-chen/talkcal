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
 * 每一次叫模型帶的逾時，真的是設定的值（#231；為什麼要自己補見 GuardedChatModel.getOptions）。
 * 助理和抽行程用的是包好保護的那個模型，都從它的 getOptions() 開始組選項，所以看它就知道兩者實際等多久。
 *
 * 起整個容器、不連資料庫，理由同 OpenApiDocumentationTest；設定一模一樣，Spring 測試會重用同一個容器。
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
@DisplayName("叫模型的逾時")
class ChatModelTimeoutTest {

    // Spring AI 沒設每次呼叫的逾時時用的值（AbstractOpenAiOptions.DEFAULT_TIMEOUT）
    private static final Duration SPRING_AI_DEFAULT = Duration.ofSeconds(60);

    @Autowired
    @Qualifier("guardedChatModel")
    ChatModel guarded;

    // Spring AI 自動組裝的那個，沒包保護
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
     * 為什麼要自己補的證據，也是升級時的提醒：Spring AI 自動組裝的選項不帶設定的逾時。
     * 哪天升級後這條紅了（Spring AI 修好了），GuardedChatModel 補逾時那段就可以拿掉。
     */
    @Test
    @DisplayName("反例：Spring AI 自動組裝的選項，每一次呼叫是它預設的 60 秒（所以 GuardedChatModel 要補）")
    void springAiAloneUsesItsDefault() {
        assertThat(((OpenAiChatOptions) springAi.getOptions()).getTimeout()).isEqualTo(SPRING_AI_DEFAULT);
    }
}
