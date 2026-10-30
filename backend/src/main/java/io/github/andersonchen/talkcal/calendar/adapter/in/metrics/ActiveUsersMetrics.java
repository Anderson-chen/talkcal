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
 * 活躍使用者數（#36）：talkcal_active_users{window="1d"|"7d"|"30d"}。
 *
 * 它是 inbound adapter：觸發的是 Alloy 來抓 /actuator/prometheus（跟 HTTP 請求觸發 controller 一樣），
 * 抓的時候才問 use case「最近這幾段時間各有幾個人在用」。
 *
 * 三個期間一次問（一句 SQL），結果留 refresh 這麼久：日活、週活、月活本來就變得很慢，
 * Alloy 每 15 秒抓一次就重算是白做工；而且查詢跑在抓指標的那條執行緒上，資料庫慢的時候整頁指標都會被拖住。
 *
 * 用 gauge、不用 counter：「有幾個人」是一個會上下變的量，不是累加的次數。
 * 不在記憶體裡累加「今天見過誰」：重啟歸零、多台各數各的（原因見 CountActiveOwnersPort）。
 *
 * 期間是固定的三個值，當成標籤不會長：日活、週活、月活是產品最常看的三個；30 天剛好是對話的保留期限，再長就數不準了。
 */
public final class ActiveUsersMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(ActiveUsersMetrics.class);

    private static final List<String> LABELS = List.of("1d", "7d", "30d");
    private static final List<Duration> WINDOWS = List.of(Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30));

    private final CountActiveUsersUseCase activeUsers;
    private final Duration refresh;
    private final Clock clock;

    // 上一次問到的結果和時間；三個 gauge 共用。null = 上次失敗
    private List<Integer> latest;
    // 從沒問過：第一次抓一定會問
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

    // 資料庫出事時回 NaN：Prometheus 把 NaN 當成「這次沒有值」，dashboard 上是斷一截，
    // 不會誤報成 0 個人在用；例外往外丟的話，Micrometer 也只是吞掉、記一筆 warn，整頁指標照樣吐
    // 失敗也留 refresh 這麼久才重問：資料庫掛了的時候，不要每次抓指標都再撞一次
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
