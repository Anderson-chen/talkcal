package io.github.andersonchen.talkcal.calendar.adapter.shared;

import java.time.Duration;
import java.util.Objects;

/**
 * 這次沒有呼叫模型：GuardedChatModel 判斷現在呼叫也只是白等，直接拒絕。
 *
 * 跟一般的模型失敗分開，意思不一樣：
 * - 一般失敗（IllegalStateException，回 502）：這次呼叫了、失敗了，下一次可能就好
 * - 這個例外（回 503 + Retry-After）：模型已經連續出錯，或同時卡在模型上的請求已經滿了，現在重試也沒用
 *
 * 繼承 IllegalStateException：ExtractEventsPort、CalendarAssistant 的契約都是失敗丟 IllegalStateException，
 * 沒有另外處理這個型別的呼叫端照樣當成一般失敗處理，契約不必改；CalendarAssistantController 另外處理，回 503。
 *
 * 放在 adapter.shared：丟出的是包著 ChatModel 的 GuardedChatModel，助理和抽行程兩邊都用，
 * 處理的是 inbound 的 controller；inbound 和 outbound 不能互相 import，共用的類別只能放這裡。
 */
public final class ModelUnavailableException extends IllegalStateException {

    /** 前端依這個值決定怎麼跟使用者說。 */
    public enum Reason {
        /** 最近呼叫模型大多失敗或太慢，先停一段時間不呼叫 */
        CIRCUIT_OPEN,
        /** 同時在等模型的請求已經滿了：再多收一個只是多一條卡住的執行緒 */
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

    public Duration retryAfter() {
        return retryAfter;
    }

    // 會原樣出現在 HTTP 回應的 error 欄位：講使用者能做什麼，不講 circuit breaker 這種內部名詞
    private static String message(Reason reason) {
        return switch (reason) {
            case CIRCUIT_OPEN -> "AI 助理暫時無法使用（模型沒有回應），請稍後再試；行事曆的其他功能照常可用";
            case BUSY -> "AI 助理現在太多人在用，請稍後再試；行事曆的其他功能照常可用";
        };
    }
}
