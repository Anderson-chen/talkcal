package io.github.andersonchen.talkcal.quota.application.domain.service;

import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaBucket;
import io.github.andersonchen.talkcal.quota.application.domain.model.QuotaRules;
import io.github.andersonchen.talkcal.quota.application.port.in.ConsumeQuotaUseCase;
import io.github.andersonchen.talkcal.quota.application.port.in.QuotaExceededException;
import io.github.andersonchen.talkcal.quota.application.port.out.LoadQuotaRulesPort;
import io.github.andersonchen.talkcal.quota.application.port.out.QuotaCounterPort;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 規則表每隔 refresh 重讀一次，不是每個請求都查資料庫：在規則表加一列，最多等 refresh 就生效，不用重啟。
 * refresh 是 0 時每次都重讀（測試用）。
 * 重讀失敗時沿用上一份；app 剛啟動就讀不到的話先當成沒有規則。
 *
 * 計數的 key 帶著日期（clock 的時區），過了午夜就是另一份，舊的到期自動消失。
 */
public final class ConsumeQuotaService implements ConsumeQuotaUseCase {

    static final String GLOBAL_SUBJECT = "all";

    private final LoadQuotaRulesPort rulesPort;
    private final QuotaCounterPort counter;
    private final Clock clock;
    private final Duration refresh;

    private volatile QuotaRules rules = QuotaRules.NONE;
    private volatile Instant loadedAt = Instant.MIN;

    public ConsumeQuotaService(LoadQuotaRulesPort rulesPort, QuotaCounterPort counter, Clock clock, Duration refresh) {
        this.rulesPort = Objects.requireNonNull(rulesPort, "rulesPort 不可為 null");
        this.counter = Objects.requireNonNull(counter, "counter 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
        this.refresh = Objects.requireNonNull(refresh, "refresh 不可為 null");
    }

    @Override
    public Optional<Consumed> consume(QuotaRequest request) {
        Objects.requireNonNull(request, "request 不可為 null");
        Optional<QuotaBucket> found = currentRules().bucketFor(request.method(), request.path(), request.tier());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        QuotaBucket bucket = found.get();
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        ZonedDateTime midnight = today.plusDays(1).atStartOfDay(now.getZone());
        String key = "quota:" + bucket.name() + ":" + subjectOf(bucket, request) + ":" + today;

        long used;
        try {
            // key 已經帶日期，晚一點消失不影響計數；到期設在午夜後一小時，只是不要剛好在換日的那一刻被刪掉
            used = counter.increment(key, midnight.plusHours(1).toInstant());
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
        if (used > bucket.dailyLimit()) {
            throw new QuotaExceededException(bucket.message(), Duration.between(now, midnight));
        }
        return Optional.of(new Consumed(key));
    }

    @Override
    public void refund(Consumed consumed) {
        Objects.requireNonNull(consumed, "consumed 不可為 null");
        try {
            counter.decrement(consumed.counterKey());
        } catch (IllegalStateException e) {
            // 退不回去只是這個人當天少一次，不讓請求本身的結果被蓋掉
        }
    }

    private static String subjectOf(QuotaBucket bucket, QuotaRequest request) {
        return switch (bucket.subject()) {
            case IP -> request.client();
            // 規則把照帳號的計數套在沒登入的等級上是規則寫錯；退回照 IP 算，不讓所有沒登入的人共用一份
            case ACCOUNT -> request.account() != null ? request.account() : request.client();
            case GLOBAL -> GLOBAL_SUBJECT;
        };
    }

    private boolean stale(Instant now) {
        return refresh.isZero() || now.isAfter(loadedAt.plus(refresh));
    }

    private QuotaRules currentRules() {
        Instant now = clock.instant();
        if (stale(now)) {
            synchronized (this) {
                if (stale(now)) {
                    try {
                        rules = rulesPort.load();
                    } catch (IllegalStateException e) {
                        // 沿用上一份，過了 refresh 再試
                    }
                    loadedAt = now;
                }
            }
        }
        return rules;
    }
}
