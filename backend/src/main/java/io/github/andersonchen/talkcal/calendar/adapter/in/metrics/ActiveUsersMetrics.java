package io.github.andersonchen.talkcal.calendar.adapter.in.metrics;

import io.github.andersonchen.talkcal.calendar.application.port.in.CountActiveUsersUseCase;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * talkcal_active_users{window="1d"|"7d"|"30d"}：Alloy 抓 /actuator/prometheus 時才呼叫 use case 取得數字。
 *
 * 三個期間一句 SQL 一起數，結果留 refresh 這麼久：活躍人數變得很慢，Alloy 每 15 秒抓一次就重算是多餘的計算；
 * 查詢也是在抓指標的那條執行緒上執行，資料庫慢的時候，整頁指標都要等這個查詢回來才送得出去。
 *
 * 用 gauge、不用 counter：人數會上下變，不是累加的次數。
 * 期間固定三個值，當成標籤不會變多。30 天剛好是對話的保留期限，再長就數不準了。
 */
public final class ActiveUsersMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(ActiveUsersMetrics.class);

    private static final List<String> LABELS = List.of("1d", "7d", "30d");
    private static final List<Duration> WINDOWS = List.of(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30));

    private final CountActiveUsersUseCase activeUsers;
    private final Duration refresh;
    private final Clock clock;

    // 三個 gauge 共用；null 表示上次失敗
    private List<Integer> latest;
    // null：從沒查過，第一次抓一定會查
    private Instant askedAt;

    public ActiveUsersMetrics(CountActiveUsersUseCase activeUsers, Duration refresh, Clock clock) {
        this.activeUsers = Objects.requireNonNull(activeUsers, "activeUsers 不可為 null");
        this.refresh = Objects.requireNonNull(refresh, "refresh 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (int i = 0; i < WINDOWS.size(); i++) {
            int index = i;
            Gauge.builder("talkcal.active.users", () -> count(index))
                    .description("最近一段時間內跟助理說過話、或存過行程的人數")
                    .tag("window", LABELS.get(i))
                    .register(registry);
        }
    }

    // 資料庫出錯時回 NaN：Prometheus 把 NaN 當成這次沒有值，dashboard 上斷一截，不會誤報成 0 人；
    // 例外往外丟的話，Micrometer 也只是吞掉、記一筆 warn。
    // 失敗也等 refresh 這麼久才重查：資料庫無法連線時，不要每次抓指標都再連一次
    private synchronized double count(int index) {
        Instant now = clock.instant();
        if (askedAt == null || !now.isBefore(askedAt.plus(refresh))) {
            askedAt = now;
            try {
                latest = activeUsers.countActiveUsers(WINDOWS);
            } catch (RuntimeException e) {
                latest = null;
                log.warn("數活躍使用者失敗，到下次重問之前都回 NaN", e);
            }
        }
        return latest == null ? Double.NaN : latest.get(index);
    }
}
