package io.github.andersonchen.talkcal.app.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * 觀測的全域規則：什麼「不」要觀測。
 *
 * 要觀測的部分不必寫程式，Spring Boot 都自動做了：
 *   進來的請求  —— Boot 自動加的 ServerHttpObservationFilter
 *   往外的呼叫  —— Spring AI 的 ChatModel 自己開的 Observation（含 token 用量指標）
 * 這裡只負責把不值得看的擋掉，和補上 Spring AI 不知道的部署資訊。
 *
 * 跟 CalendarConfiguration 同放在 app.config（組裝都集中在這裡），但分成兩個類別：
 * 那邊回答「calendar 模組誰接誰」，這邊回答的是整個 app 共用的維運決定，不屬於任何一個功能模組。
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    // /actuator/* 的請求不觀測：不開 trace、也不記 http.server.requests 指標。
    //
    // 為什麼：ops/ 的 Alloy 每 15 秒來抓一次 /actuator/prometheus、Docker 每幾秒打一次 /actuator/health，
    // 一小時就是幾百筆 trace，真正的請求在 Tempo 上被淹掉（跟 AccessLogFilter 不記 actuator 同一個理由）。
    // 這些請求的健康本身已經有人看著：抓不到時 Mimir 裡的 up 會變 0，健康檢查失敗時 Docker 會標 unhealthy。
    //
    // 為什麼用 ObservationPredicate 而不是只關 trace：trace 和指標都從同一個 Observation 長出來，
    // 在源頭擋掉最單純。代價是 /actuator 的 http.server.requests 也沒了 —— 儀表板本來就把它濾掉，不受影響。
    @Bean
    ObservationPredicate skipActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith("/actuator"));
    }

    // 每次叫模型，在 Spring AI 的觀測上多貼一個 model.backend 標籤：模型是在哪裡跑的（#35）。
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
