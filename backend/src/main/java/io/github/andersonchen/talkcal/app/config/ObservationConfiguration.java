package io.github.andersonchen.talkcal.app.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 觀測的全域規則：補上 Spring AI 不知道的部署資訊。
 *
 * 要觀測的部分不必寫程式，Spring Boot 都自動做了：
 *   進來的請求  —— Boot 自動加的 ServerHttpObservationFilter
 *   往外的呼叫  —— Spring AI 的 ChatModel 自己開的 Observation（含 token 用量指標）
 *
 * 跟 CalendarConfiguration 同放在 app.config（組裝都集中在這裡），但分成兩個類別：
 * 那邊回答「calendar 模組誰接誰」，這邊回答的是整個 app 共用的維運決定，不屬於任何一個功能模組。
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    // 每次叫模型，在 Spring AI 的觀測上多貼一個 model.backend 標籤：模型是在哪裡跑的。
    //
    // token 用量本身不必自己記：Spring AI 每次叫完模型會把回應裡的 usage 記成 gen_ai.client.token.usage
    // （input、output、total 各一個 counter），標籤照抄這次觀測的 low-cardinality 標籤 —— 所以在這裡多貼一個，
    // token 指標、gen_ai.client.operation 的延遲、trace 上的 span 就都帶著它。
    //
    // 為什麼要多這個標籤：本機 llama-server 和雲端 Bedrock 都是用 OpenAI 的 client 打的，
    // Spring AI 內建的 provider 標籤（gen_ai.system）兩邊都是 openai，分不出來。模型名稱分得出「哪一顆」，
    // 但算錢要的是「在哪裡跑」：Bedrock 照 token 計價，自己跑的是機器時薪，跟 token 數無關。
    // 值是部署決定，所以從設定讀：本機和 gpu 機型是 llama-cpp，compose.bedrock.yaml 蓋成 bedrock。
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
