package io.github.andersonchen.talkcal.quota.application.port.in;

import java.time.Duration;
import java.util.Objects;

/** 在 HTTP 對應到 429。訊息直接給使用者看；retryAfter 是離換日還有多久，換日後重新計算 */
public final class QuotaExceededException extends RuntimeException {

    private final Duration retryAfter;

    public QuotaExceededException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter 不可為 null");
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
