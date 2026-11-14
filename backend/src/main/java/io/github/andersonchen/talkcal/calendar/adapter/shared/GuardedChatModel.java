package io.github.andersonchen.talkcal.calendar.adapter.shared;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Objects;

/**
 * 包在 ChatModel 外面的兩道保護：模型無法回應時，助理很快失敗，其他功能照常可用。
 *
 * <pre>
 * 請求 → bulkhead：同時在等模型的已經滿了？ 滿了 → 立刻拒絕（BUSY）
 *           │
 *           └→ circuit breaker：最近大多失敗或太慢？ 是 → 立刻拒絕（CIRCUIT_OPEN），不呼叫模型
 *                 │
 *                 └→ 呼叫模型，結果（成功、失敗、花多久）記進 breaker
 * </pre>
 *
 * - circuit breaker 擋的是明知會失敗還呼叫：模型停止回應之後，每個請求都要各自等一次失敗（最久到 spring.ai.openai.chat.timeout）；
 *   breaker 打開後直接回 503，過一段時間再放少數幾個請求去試，試成功了才關回來
 * - bulkhead 擋的是卡住的請求占滿執行緒：模型沒有停止、只是不回應的時候，breaker 要等請求逾時才算失敗，
 *   這段期間每個助理請求都占著一條 Tomcat 執行緒。有上限之後，行程 CRUD 一定分得到執行緒
 *
 * bulkhead 在外層：bulkhead 滿了不是模型的錯，不該記進 breaker、讓 breaker 打開。
 *
 * breaker、bulkhead 由 Resilience4j 的 starter 照 application.properties 的 resilience4j.*.instances.chat-model 建立
 * （門檻的理由寫在那裡）。所有呼叫模型的地方共用這一組：背後是同一個模型，要壞一起壞。
 * 什麼失敗算模型出錯，見 ModelFailurePredicate。每一次呼叫最多等多久，見 getOptions。
 */
public final class GuardedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Duration retryAfterOpen;
    private final Duration retryAfterBusy;
    private final ChatOptions options;

    /**
     * @param retryAfterBusy 滿載時建議多久後再試。斷路器打開時的建議時間不另外給：就是 breaker 保持打開的時間，
     *                       直接從 breaker 的設定讀，不在兩個地方各寫一次
     * @param callTimeout    每一次呼叫模型最多等多久（見 getOptions）
     */
    public GuardedChatModel(ChatModel delegate, CircuitBreaker breaker, Bulkhead bulkhead, Duration retryAfterBusy,
                            Duration callTimeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不可為 null");
        this.breaker = Objects.requireNonNull(breaker, "breaker 不可為 null");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead 不可為 null");
        this.retryAfterOpen = Duration.ofMillis(breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1));
        this.retryAfterBusy = Objects.requireNonNull(retryAfterBusy, "retryAfterBusy 不可為 null");
        this.options = withTimeout(delegate.getOptions(), Objects.requireNonNull(callTimeout, "callTimeout 不可為 null"));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return bulkhead.executeSupplier(() -> breaker.executeSupplier(() -> delegate.call(prompt)));
        } catch (CallNotPermittedException e) {
            throw new ModelUnavailableException(ModelUnavailableException.Reason.CIRCUIT_OPEN, retryAfterOpen, e);
        } catch (BulkheadFullException e) {
            throw new ModelUnavailableException(ModelUnavailableException.Reason.BUSY, retryAfterBusy, e);
        }
    }

    /**
     * 保護只做在 call() 上，串流要另外處理開始回應才失敗、回到一半卡住的情況，目前沒有地方用串流。
     * 明確丟例外、不用 ChatModel 的預設實作：之後要串流的人一看就知道要先補保護，不會改用沒包裝的那個、繞過斷路器。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new UnsupportedOperationException("GuardedChatModel 只保護 call()，串流還沒有斷路器、bulkhead");
    }

    /**
     * 沿用被包住的模型的預設選項（模型名稱、max-tokens…，也就是 application.properties 的設定），只多帶每一次呼叫的逾時。
     *
     * Spring AI 2.0.1 的逾時有兩層：spring.ai.openai.chat.timeout 是建立 client 時的，每一次呼叫另外帶選項裡的 timeout，
     * 而且會覆蓋前者。自動組裝的預設選項不帶逾時（spring.ai.openai.chat.options.timeout 綁得進去，但組選項時沒有讀），
     * 所以每次呼叫都用 Spring AI 預設的 60 秒。呼叫模型的地方都從這份預設開始組選項，在這裡補一次就都有。
     */
    @Override
    public ChatOptions getOptions() {
        return options;
    }

    // 不是 OpenAI 的選項（例如測試用的假模型）就不改：只有 OpenAI 的選項會把 timeout 當成每次呼叫的逾時
    private static ChatOptions withTimeout(ChatOptions defaults, Duration callTimeout) {
        return defaults instanceof OpenAiChatOptions openAi ? openAi.mutate().timeout(callTimeout).build() : defaults;
    }
}
