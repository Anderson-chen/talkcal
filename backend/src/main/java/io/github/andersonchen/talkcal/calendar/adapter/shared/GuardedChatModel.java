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
 * 包在真正的 ChatModel 外面的兩道保護（#44）：模型掛了，助理快速失敗，其他功能照常可用。
 *
 * <pre>
 * 請求 ─→ bulkhead：同時在等模型的已經滿了？ ─滿了→ 立刻拒絕（BUSY）
 *           │
 *           └→ circuit breaker：最近大多失敗或太慢？ ─是→ 立刻拒絕（CIRCUIT_OPEN），不叫模型
 *                 │
 *                 └→ 真的叫模型，結果（成功、失敗、花多久）記進 breaker
 * </pre>
 *
 * 兩道擋的是不同的事：
 * - circuit breaker 擋「明知道會失敗還叫」：模型掛了之後，每個請求都還要各自等一次失敗（最久 2 分鐘逾時），
 *   breaker 打開後直接回 503，等一段時間再放少數幾個請求去試，試成功了才關回來
 * - bulkhead 擋「卡住的請求把執行緒吃光」：模型沒死、只是不回（卡住）的時候，breaker 要等請求逾時才知道失敗，
 *   這段期間每個助理請求都佔著一條 Tomcat 執行緒。上限擋住之後，行程 CRUD 永遠留得到執行緒
 *
 * bulkhead 在外層：bulkhead 滿了不是模型的錯，不該記進 breaker 讓它打開。
 *
 * breaker、bulkhead 由 Resilience4j 的 starter 照 application.properties 的 resilience4j.*.instances.chat-model 建好，
 * app.config 依名字取出來交進來（門檻是部署決定，理由寫在 application.properties）；
 * 助理和抽行程各包一個 GuardedChatModel，但共用同一組 breaker、bulkhead —— 背後是同一顆模型，壞就一起壞。
 * 什麼失敗算模型出事，見 ModelFailurePredicate。
 *
 * 另外補上每一次呼叫最多等多久（#231，見 getOptions）：這也是保護的一部分，而且 Spring AI 自己沒做到。
 */
public final class GuardedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Duration retryAfterOpen;
    private final Duration retryAfterBusy;
    private final ChatOptions options;

    /**
     * @param retryAfterBusy 滿載時建議多久後再試。
     *                       熔斷打開時的建議時間不必給：就是 breaker 保持打開的時間，早於這個時間重試一定還是被擋，
     *                       直接從 breaker 的設定讀，免得兩個地方各寫一次、改了一邊忘了另一邊
     * @param callTimeout    每一次叫模型最多等多久（見 getOptions）
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
     * 不支援串流：保護只做在 call() 上（串流要另外處理「開始回了才失敗」「回到一半卡住」，現在沒有人用）。
     * 明寫出來而不是靠 ChatModel 的預設：之後有人要串流時，一看就知道要先補保護，不會改去拿沒包裝的那個、繞過斷路器。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new UnsupportedOperationException("GuardedChatModel 只保護 call()，串流還沒有斷路器、bulkhead");
    }

    /**
     * 預設選項：照原本的（模型名稱、max-tokens…，呼叫端靠它沿用 application.properties 的設定），只多帶上每一次呼叫的逾時。
     *
     * 為什麼要在這裡補（#231）：Spring AI 2.0.1 的逾時有兩層，spring.ai.openai.chat.timeout 是建 client 時的，
     * 每一次呼叫另外帶選項裡的 timeout，而且會蓋掉前者。自動組裝的預設選項不會帶上任何逾時
     * （spring.ai.openai.chat.options.timeout 綁得進去、但組選項時沒讀它），於是每次呼叫都用 Spring AI 預設的 60 秒。
     * 助理和抽行程都從這份預設開始組自己的選項，在這裡補一次兩邊都拿到。
     */
    @Override
    public ChatOptions getOptions() {
        return options;
    }

    // 不是 OpenAI 那套選項（例如測試用的假模型）就不動：只有 OpenAI 那套會把它變成每次呼叫的逾時
    private static ChatOptions withTimeout(ChatOptions defaults, Duration callTimeout) {
        return defaults instanceof OpenAiChatOptions openAi ? openAi.mutate().timeout(callTimeout).build() : defaults;
    }
}
