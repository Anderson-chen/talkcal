package io.github.andersonchen.talkcal.calendar.adapter.out.metrics.micrometer;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 產品用量記成 Micrometer 的 counter，經 /actuator/prometheus 讓 Alloy 抓進 Mimir（#36）。
 *
 * 指標（Prometheus 上的名字）：
 * - talkcal_assistant_proposals_shown_total       助理給使用者看了幾張提議卡片
 * - talkcal_assistant_proposals_dismissed_total   其中被按掉（取消、移除）的
 * - talkcal_calendar_events_added_total{origin}   存進行事曆的行程，origin = assistant（採納）或 manual（手動）
 *
 * 標籤只有 origin 一個、兩個值：每個標籤值的組合就是一條時間序列，放使用者 ID、行程標題這種會一直長的值，
 * Mimir 的序列數就跟著人數長（基數爆炸），查詢變慢、費用變高。「誰」的問題留給 log 和資料庫。
 *
 * counter 在建構時就先建好，不等第一次發生才建：從來沒發生過的事在 Prometheus 上是「沒有這條序列」，
 * 不是 0 —— 採納率的分母分子有一邊不存在，dashboard 會整格空白，看起來像壞掉。
 */
public final class MicrometerUsageAdapter implements RecordUsagePort {

    private final Counter shown;
    private final Counter dismissed;
    private final Map<EventOrigin, Counter> added = new EnumMap<>(EventOrigin.class);

    public MicrometerUsageAdapter(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry 不可為 null");
        shown = Counter.builder("talkcal.assistant.proposals.shown")
                .description("AI 助理給使用者看的提議卡片數")
                .register(registry);
        dismissed = Counter.builder("talkcal.assistant.proposals.dismissed")
                .description("使用者按掉（取消、移除）的提議卡片數")
                .register(registry);
        for (EventOrigin origin : EventOrigin.values()) {
            added.put(origin, Counter.builder("talkcal.calendar.events.added")
                    .description("存進行事曆的行程數；origin=assistant 是採納助理的提議")
                    .tag("origin", tagValue(origin))
                    .register(registry));
        }
    }

    @Override
    public void proposalsShown(int count) {
        shown.increment(count);
    }

    @Override
    public void proposalsDismissed(int count) {
        dismissed.increment(count);
    }

    @Override
    public void eventsAdded(EventOrigin origin, int count) {
        added.get(origin).increment(count);
    }

    // 標籤值在這裡寫死，不用 origin.name().toLowerCase()：enum 改名不該順手改掉 dashboard 查的值。
    // 沒有 default：EventOrigin 多了新值，這裡會編譯失敗，逼你決定它在指標上叫什麼
    private static String tagValue(EventOrigin origin) {
        return switch (origin) {
            case ASSISTANT -> "assistant";
            case MANUAL -> "manual";
        };
    }
}
