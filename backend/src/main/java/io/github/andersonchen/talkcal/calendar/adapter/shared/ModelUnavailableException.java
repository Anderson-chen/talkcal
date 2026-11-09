package io.github.andersonchen.talkcal.calendar.adapter.shared;

import java.time.Duration;
import java.util.Objects;

/**
 * 模型這次沒有被呼叫：保護機制（GuardedChatModel）判斷現在叫了也是白等，直接拒絕（#44）。
 *
 * 跟一般的模型失敗分開，因為意思不一樣：
 * - 一般失敗（IllegalStateException，回 502）：這一次叫了、壞了，下一次可能就好
 * - 這個（回 503 + Retry-After）：模型已經連續出事，或同時卡在模型上的請求已經滿了 —— 現在重試也沒用，過一陣子再來
 *
 * 繼承 IllegalStateException：ExtractEventsPort、CalendarAssistant 的契約都是「失敗丟 IllegalStateException」，
 * 沒認得這個型別的呼叫端照樣當成一般失敗處理，契約不必改；認得它的（CalendarAssistantController）才回 503。
 *
 * 放在 adapter.shared：丟它的是包著 ChatModel 的 GuardedChatModel，兩側的 adapter（助理、抽行程）都用同一個模型，
 * 接它的是 inbound 的 controller。兩側互不認識，共同的東西只能放在這裡（跟 DateTable 同一個理由）。
 */
public final class ModelUnavailableException extends IllegalStateException {

    /** 為什麼沒叫模型。前端靠它決定怎麼跟使用者說（#43）。 */
    public enum Reason {
        /** 熔斷打開：最近叫模型大多失敗或太慢，先停一陣子不叫 */
        CIRCUIT_OPEN,
        /** 同時在等模型的請求已經滿了：再多收一個只是多一個卡住的執行緒 */
        BUSY
    }

    private final Reason reason;
    private final Duration retryAfter;

    public ModelUnavailableException(Reason reason, Duration retryAfter, Throwable cause) {
        super(message(reason), cause);
        this.reason = Objects.requireNonNull(reason, "reason 不可為 null");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter 不可為 null");
    }

    public Reason reason() {
        return reason;
    }

    /** 建議多久之後再試。 */
    public Duration retryAfter() {
        return retryAfter;
    }

    // 給人看的，也會原樣出現在 HTTP 回應的 error 欄位：講使用者能做什麼，不講 circuit breaker 這種內部名詞
    private static String message(Reason reason) {
        return switch (reason) {
            case CIRCUIT_OPEN -> "AI 助理暫時無法使用（模型沒有回應），請稍後再試；行事曆的其他功能照常可用";
            case BUSY -> "AI 助理現在太多人在用，請稍後再試；行事曆的其他功能照常可用";
        };
    }
}
