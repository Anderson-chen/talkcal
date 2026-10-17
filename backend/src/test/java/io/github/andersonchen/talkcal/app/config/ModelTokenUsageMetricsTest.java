package io.github.andersonchen.talkcal.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 每叫一次模型，token 用量變成指標，帶著模型名稱和 model.backend —— 成本 dashboard 全靠它（#35）。
 *
 * 為什麼起整個 Spring：要驗的是「接起來之後」真的有記到。token 指標是 Spring AI 的 handler 記的，
 * 標籤是我們的 ObservationFilter 貼的，兩者都要被 Boot 註冊進同一個 ObservationRegistry 才成立；
 * 少註冊一個不會有任何錯誤訊息，指標只是悄悄少一個標籤，或整個不見。
 *
 * 模型用一個假的 OpenAI server（JDK 內建的 HttpServer）代替：回一個寫死的 Chat Completions JSON，
 * 讓真的 OpenAiChatModel 和 openai-java SDK 去解析 usage —— 這樣連「SDK 有沒有把 usage 交給 Spring AI」也一起驗到。
 * 不需要資料庫：只叫 ChatModel，不經過助理和記憶；Flyway 關掉，理由同 AccessLogTraceIdTest。
 *
 * backend 刻意設成 bedrock 而不是用預設的 llama-cpp：證明標籤的值是從設定來的，不是程式裡寫死的。
 * 每個測試讓假 server 回不同的模型名稱，指標靠 gen_ai.response.model 分開，測試之間不會互相加到同一個 counter。
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false", "talkcal.ai.backend=bedrock"})
@DisplayName("模型的 token 用量指標")
class ModelTokenUsageMetricsTest {

    private static final HttpServer FAKE_OPENAI = startFakeOpenAi();
    // 下一次 /chat/completions 要回什麼；每個測試自己設
    private static volatile String nextResponse;

    @Autowired
    ChatModel chatModel;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ObservationRegistry observations;

    @DynamicPropertySource
    static void pointModelAtFakeServer(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.chat.base-url",
                () -> "http://127.0.0.1:" + FAKE_OPENAI.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stopFakeServer() {
        FAKE_OPENAI.stop(0);
    }

    @Test
    @DisplayName("回應帶 usage：input、output 各記成一個 counter，數字跟 usage 一樣，標著模型和 backend")
    void recordsInputAndOutputTokens() {
        nextResponse = completion("qwen-with-usage", """
                ,"usage":{"prompt_tokens":120,"completion_tokens":34,"total_tokens":154}""");

        chatModel.call(new Prompt("明天下午三點開會"));

        Counter input = tokens("qwen-with-usage", "input");
        Counter output = tokens("qwen-with-usage", "output");
        assertNotNull(input, "沒有 input 的 token 指標，現有的是：" + tokenMeters());
        assertNotNull(output, "沒有 output 的 token 指標，現有的是：" + tokenMeters());
        assertEquals(120, input.count());
        assertEquals(34, output.count());
        // 送出去的模型名稱（application.properties 的 qwen3-8b）也在，dashboard 依它分顆
        assertEquals("qwen3-8b", input.getId().getTag("gen_ai.request.model"));
    }

    @Test
    @DisplayName("反例：回應沒帶 usage 時記成 0 —— 數字只來自這次的回應，不會猜、也不會拿別次的充數")
    void noUsageCountsAsZero() {
        nextResponse = completion("qwen-no-usage", "");

        chatModel.call(new Prompt("明天下午三點開會"));

        // Spring AI 沒拿到 usage 時用 EmptyUsage（0、0、0），counter 照樣建、加 0。
        // 意思是：哪天供應商不回 usage（例如改成串流又沒開 include_usage），dashboard 上的錢會悄悄變少而不是報錯。
        // 成本 dashboard 的「每次叫模型平均 token」掉到 0 就是這個訊號（見 docs/cost.md）
        Counter input = tokens("qwen-no-usage", "input");
        assertNotNull(input, "現有的是：" + tokenMeters());
        assertEquals(0, input.count());
        // 這次呼叫本身有被觀測到，也帶著 backend
        Timer call = meters.find("gen_ai.client.operation").tag("gen_ai.response.model", "qwen-no-usage").timer();
        assertNotNull(call, "這次呼叫根本沒被觀測到，上面的斷言不算數");
        assertEquals("bedrock", call.getId().getTag("model.backend"));
    }

    @Test
    @DisplayName("反例：不是叫模型的觀測不貼 model.backend")
    void otherObservationsUntagged() {
        Observation.createNotStarted("talkcal.test.not-a-model-call", observations).observe(() -> { });

        Timer timer = meters.find("talkcal.test.not-a-model-call").timer();
        assertNotNull(timer);
        assertNull(timer.getId().getTag("model.backend"));
    }

    private Counter tokens(String responseModel, String type) {
        Counter counter = meters.find("gen_ai.client.token.usage")
                .tag("gen_ai.response.model", responseModel)
                .tag("gen_ai.token.type", type)
                .counter();
        if (counter != null) {
            // 每個 token counter 都要能分出在哪裡跑、哪一顆，少一個標籤 dashboard 就算錯錢
            assertEquals("bedrock", counter.getId().getTag("model.backend"), "標籤：" + counter.getId().getTags());
            assertTrue(counter.getId().getTag("gen_ai.request.model") != null, "標籤：" + counter.getId().getTags());
        }
        return counter;
    }

    private String tokenMeters() {
        return meters.find("gen_ai.client.token.usage").counters().stream().map(c -> c.getId().toString()).toList().toString();
    }

    /** 一個最小的 Chat Completions 回應；usage 那段由呼叫的人決定要不要帶。 */
    private static String completion(String model, String usage) {
        return """
                {"id":"chatcmpl-test","object":"chat.completion","created":1760000000,"model":"%s",\
                "choices":[{"index":0,"message":{"role":"assistant","content":"好"},"finish_reason":"stop"}]%s}"""
                .formatted(model, usage);
    }

    private static HttpServer startFakeOpenAi() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = nextResponse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
