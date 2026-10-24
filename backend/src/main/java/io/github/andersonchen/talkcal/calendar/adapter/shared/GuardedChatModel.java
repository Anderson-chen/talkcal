package io.github.andersonchen.talkcal.calendar.adapter.shared;

import com.openai.errors.BadRequestException;
import com.openai.errors.UnprocessableEntityException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

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
 * 兩個物件由 app.config 建好、設好門檻再交進來（門檻是部署決定，理由寫在 application.properties）；
 * 助理和抽行程各包一個 GuardedChatModel，但共用同一組 breaker、bulkhead —— 背後是同一顆模型，壞就一起壞。
 */
public final class GuardedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final Duration retryAfterOpen;
    private final Duration retryAfterBusy;

    /**
     * @param retryAfterOpen 熔斷打開時建議多久後再試：跟 breaker 保持打開的時間一樣，早於這個時間重試一定還是被擋
     * @param retryAfterBusy 滿載時建議多久後再試
     */
    public GuardedChatModel(ChatModel delegate, CircuitBreaker breaker, Bulkhead bulkhead,
                            Duration retryAfterOpen, Duration retryAfterBusy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate 不可為 null");
        this.breaker = Objects.requireNonNull(breaker, "breaker 不可為 null");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead 不可為 null");
        this.retryAfterOpen = Objects.requireNonNull(retryAfterOpen, "retryAfterOpen 不可為 null");
        this.retryAfterBusy = Objects.requireNonNull(retryAfterBusy, "retryAfterBusy 不可為 null");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, () -> delegate.call(prompt))).get();
        } catch (CallNotPermittedException e) {
            throw new ModelUnavailableException(ModelUnavailableException.Reason.CIRCUIT_OPEN, retryAfterOpen, e);
        } catch (BulkheadFullException e) {
            throw new ModelUnavailableException(ModelUnavailableException.Reason.BUSY, retryAfterBusy, e);
        }
    }

    // 預設選項（模型名稱、max-tokens…）照原本的：呼叫端靠它沿用 application.properties 的設定
    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    /**
     * 這個失敗算不算「模型出事」，要不要記進 breaker。
     *
     * 不算的只有「這一個請求本身有問題」：400（例如對話太長超過 context）、422。換一個請求就會好，
     * 算進去的話，一個使用者一直送超長的對話就能把所有人的助理關掉。
     * 其他都算：連不上、逾時、5xx、429（模型塞爆）、401／403（雲端的金鑰失效）、404（模型名稱設錯）——
     * 這些換一個請求也一樣失敗，正是 breaker 要擋的。
     *
     * 沿著 cause 往下找：Spring AI 可能把 SDK 的例外再包一層。
     */
    public static boolean countsAgainstModel(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BadRequestException || t instanceof UnprocessableEntityException) {
                return false;
            }
        }
        return true;
    }
}
