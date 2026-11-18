package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.calendar.adapter.in.web.CalendarAssistantController;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    // Spring AI 每次呼叫模型之後會記 gen_ai.client.token.usage 等指標，標籤照抄這次觀測的 low-cardinality 標籤；
    // 在觀測上多貼 model.backend，token 指標、gen_ai.client.operation 的延遲、trace 的 span 就都帶著這個標籤。
    //
    // 本機 llama-server 和雲端 Bedrock 都是用 OpenAI 的 client 呼叫的，Spring AI 內建的 gen_ai.system 兩邊都是 openai，
    // 只看這個標籤分不出模型在哪裡執行。各個值和算錢怎麼用，見 application.properties 的 talkcal.ai.backend。
    //
    // 只貼在呼叫模型的觀測上：其他指標多一個永遠同值的標籤，只會讓每條時間序列變長
    @Bean
    ObservationFilter tagModelBackend(@Value("${talkcal.ai.backend}") String backend) {
        KeyValue tag = KeyValue.of("model.backend", backend);
        return context -> context instanceof ChatModelObservationContext
                ? context.addLowCardinalityKeyValue(tag)
                : context;
    }

    // 沒登入的訪客也送 POST /api/calendar/assistant，拿到的是預先寫好的回答（幾毫秒、不呼叫模型）。
    // 不分開的話，訪客的請求會算進 assistant-* SLO 的好事件，模型變慢、502 變多都被稀釋（見 docs/slo.md）。
    //
    // 每個 HTTP 請求都貼，不只貼訪客的：Prometheus 同一個指標的每條序列要有同一組標籤名稱，
    // 有的有 guest、有的沒有的話，Micrometer 的 Prometheus registry 會拒絕登記標籤名稱不一樣的那些序列。
    // 在結束時貼（ObservationFilter 在 stop 時套用）：controller 執行時才知道是不是訪客
    @Bean
    ObservationFilter tagGuestRequests() {
        KeyValue guest = KeyValue.of("guest", "true");
        KeyValue member = KeyValue.of("guest", "false");
        return context -> context instanceof ServerRequestObservationContext server
                ? context.addLowCardinalityKeyValue(
                        server.getCarrier().getAttribute(CalendarAssistantController.GUEST_REQUEST) != null ? guest : member)
                : context;
    }
}
