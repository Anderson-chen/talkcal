package io.github.andersonchen.talkcal.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * 成本 dashboard 靠這些 token 指標和它們的標籤。
 *
 * 起整個 Spring：token 指標是 Spring AI 的 handler 記的，標籤是 ObservationConfiguration 的 filter 貼的，
 * 兩個都要被 Boot 註冊進同一個 ObservationRegistry。少註冊一個不會有錯誤訊息，指標只會少一個標籤或整個不見。
 *
 * 模型換成 JDK 內建 HttpServer 做的假 OpenAI server，回寫死的 Chat Completions JSON，
 * usage 由真的 OpenAiChatModel 和 openai-java SDK 解析，SDK 有沒有把 usage 交給 Spring AI 也一起驗到。
 * 只叫 ChatModel、不碰資料庫，所以 Flyway 關掉（見 AccessLogTraceIdTest）。
 *
 * backend 設成 bedrock，不用預設的 llama-cpp：值跟預設不同，才驗得出標籤是從設定讀的。
 * 每個測試讓假 server 回不同的模型名稱，counter 靠 gen_ai.response.model 分開，測試之間不會加到同一個。
 */
@SpringBootTest(properties = {"spring.flyway.enabled=false", "talkcal.ai.backend=bedrock"})
@DisplayName("模型的 token 用量指標")
class ModelTokenUsageMetricsTest {

    private static final HttpServer FAKE_OPENAI = startFakeOpenAi();
    // 假 server 下一次回什麼，每個測試自己設
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
        // application.properties 設的模型名稱，dashboard 用它分模型
        assertEquals("qwen3-8b", input.getId().getTag("gen_ai.request.model"));
    }

    @Test
    @DisplayName("反例：回應沒帶 usage 時記成 0 —— 數字只來自這次的回應，不會猜、也不會拿別次的充數")
    void noUsageCountsAsZero() {
        nextResponse = completion("qwen-no-usage", "");

        chatModel.call(new Prompt("明天下午三點開會"));

        // Spring AI 沒拿到 usage 就記 0，不報錯。供應商不回 usage 時（例如串流又沒開 include_usage），
        // dashboard 上的花費會悄悄變少；能看出來的只有「每次叫模型平均 token」掉到 0（見 docs/cost.md）
        Counter input = tokens("qwen-no-usage", "input");
        assertNotNull(input, "現有的是：" + tokenMeters());
        assertEquals(0, input.count());
        // 呼叫有被觀測到，上面的 0 才不是因為根本沒記
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
            // 少一個標籤，dashboard 就分不出是哪個後端、哪個模型花的錢
            assertEquals("bedrock", counter.getId().getTag("model.backend"), "標籤：" + counter.getId().getTags());
            assertNotNull(counter.getId().getTag("gen_ai.request.model"), "標籤：" + counter.getId().getTags());
        }
        return counter;
    }

    private String tokenMeters() {
        return meters.find("gen_ai.client.token.usage").counters().stream().map(c -> c.getId().toString()).toList().toString();
    }

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
