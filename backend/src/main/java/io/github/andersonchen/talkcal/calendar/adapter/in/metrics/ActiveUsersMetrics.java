package io.github.andersonchen.talkcal.calendar.adapter.in.metrics;

import io.github.andersonchen.talkcal.calendar.application.port.in.CountActiveUsersUseCase;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * 活躍使用者數（#36）：talkcal_active_users{window="1d"|"7d"|"30d"}。
 *
 * 它是 inbound adapter：觸發的是 Alloy 來抓 /actuator/prometheus（跟 HTTP 請求觸發 controller 一樣），
 * 抓的當下才問 use case「最近這段時間有幾個人在用」，所以數字永遠是當下從資料庫數出來的。
 *
 * 用 gauge、不用 counter：「有幾個人」是一個會上下變的量，不是累加的次數。
 * 不在記憶體裡累加「今天見過誰」：重啟歸零、多台各數各的（原因見 CountActiveOwnersPort）。
 *
 * 期間是固定的三個值，當成標籤不會長：日活、週活、月活是產品最常看的三個；30 天剛好是對話的保留期限，再長就數不準了。
 */
public final class ActiveUsersMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(ActiveUsersMetrics.class);

    private final CountActiveUsersUseCase activeUsers;

    public ActiveUsersMetrics(CountActiveUsersUseCase activeUsers) {
        this.activeUsers = Objects.requireNonNull(activeUsers, "activeUsers 不可為 null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        bind(registry, "1d", Duration.ofDays(1));
        bind(registry, "7d", Duration.ofDays(7));
        bind(registry, "30d", Duration.ofDays(30));
    }

    private void bind(MeterRegistry registry, String label, Duration window) {
        Gauge.builder("talkcal.active.users", () -> count(window))
                .description("最近一段時間內跟助理說過話、或存過行程的人數")
                .tag("window", label)
                .register(registry);
    }

    // 資料庫出事時回 NaN：Prometheus 把 NaN 當成「這次沒有值」，dashboard 上是斷一截，
    // 不會誤報成 0 個人在用；例外往外丟的話，Micrometer 也只是吞掉、記一筆 warn，整頁指標照樣吐
    private double count(Duration window) {
        try {
            return activeUsers.countActiveUsers(window);
        } catch (RuntimeException e) {
            log.warn("數活躍使用者失敗，這次回 NaN：window={}", window, e);
            return Double.NaN;
        }
    }
}
