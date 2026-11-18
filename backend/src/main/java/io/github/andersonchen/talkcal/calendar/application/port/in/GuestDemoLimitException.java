package io.github.andersonchen.talkcal.calendar.application.port.in;

import java.time.Duration;
import java.util.Objects;

/** 在 HTTP 對應到 429。retryAfter 是離換日還有多久，換日後次數重算 */
public final class GuestDemoLimitException extends RuntimeException {

    private final Duration retryAfter;

    public GuestDemoLimitException(Duration retryAfter) {
        super("今天的示範次數用完了，註冊後就能直接跟助理說話");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter 不可為 null");
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
