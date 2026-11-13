package io.github.andersonchen.talkcal.app.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 要觀測的部分不必寫程式，Spring Boot 都自動做了：
 *   進來的請求  —— Boot 自動加的 ServerHttpObservationFilter
 *   往外的呼叫  —— Spring AI 的 ChatModel 自己開的 Observation（含 token 用量指標）
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    // token 用量本身不必自己記：Spring AI 每次叫完模型會把回應裡的 usage 記成 gen_ai.client.token.usage
    // （input、output、total 各一個 counter），標籤照抄這次觀測的 low-cardinality 標籤，所以在這裡多貼一個 model.backend，
    // token 指標、gen_ai.client.operation 的延遲、trace 上的 span 就都帶著它。
    //
    // 為什麼要多這個標籤：本機 llama-server 和雲端 Bedrock 都是用 OpenAI 的 client 打的，
    // Spring AI 內建的 provider 標籤（gen_ai.system）兩邊都是 openai，分不出來；模型名稱也只分得出「哪一顆」。
    // 各個值是什麼、算錢怎麼用，見 application.properties 的 talkcal.ai.backend。
    //
    // 只貼在叫模型的觀測上：http.server.requests 這些多一個永遠同值的標籤沒有意義，只會讓每條時間序列變長
    @Bean
    ObservationFilter tagModelBackend(@Value("${talkcal.ai.backend}") String backend) {
        KeyValue tag = KeyValue.of("model.backend", backend);
        return context -> context instanceof ChatModelObservationContext
                ? context.addLowCardinalityKeyValue(tag)
                : context;
    }
}
