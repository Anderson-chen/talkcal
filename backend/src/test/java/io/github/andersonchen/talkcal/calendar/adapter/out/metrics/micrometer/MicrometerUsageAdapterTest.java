package io.github.andersonchen.talkcal.calendar.adapter.out.metrics.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventOrigin;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指標的名字和標籤就是 dashboard 查詢的契約：這裡寫死，改了名字這裡會紅，提醒要一起改 talkcal-product.json。
 */
@DisplayName("MicrometerUsageAdapter")
class MicrometerUsageAdapterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerUsageAdapter adapter = new MicrometerUsageAdapter(registry);

    private double count(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    @DisplayName("還沒發生過也先有一條 0：dashboard 的採納率才算得出來，不會整格空白")
    void startsAtZero() {
        assertEquals(0, count("talkcal.assistant.proposals.shown"));
        assertEquals(0, count("talkcal.assistant.proposals.dismissed"));
        assertEquals(0, count("talkcal.calendar.events.added", "origin", "assistant"));
        assertEquals(0, count("talkcal.calendar.events.added", "origin", "manual"));
    }

    @Test
    @DisplayName("給人看 3 張、採納 1、拒絕 2、手動 1：各自累加到對的地方")
    void countsEachEvent() {
        adapter.proposalsShown(3);
        adapter.eventsAdded(EventOrigin.ASSISTANT, 1);
        adapter.proposalsDismissed(2);
        adapter.eventsAdded(EventOrigin.MANUAL, 1);

        assertEquals(3, count("talkcal.assistant.proposals.shown"));
        assertEquals(1, count("talkcal.calendar.events.added", "origin", "assistant"));
        assertEquals(2, count("talkcal.assistant.proposals.dismissed"));
        assertEquals(1, count("talkcal.calendar.events.added", "origin", "manual"));
    }

    @Test
    @DisplayName("反例：被拒絕的不算採納、手動的也不算採納")
    void dismissedAndManualAreNotAdoption() {
        adapter.proposalsDismissed(5);
        adapter.eventsAdded(EventOrigin.MANUAL, 4);

        assertEquals(0, count("talkcal.calendar.events.added", "origin", "assistant"));
    }

    @Test
    @DisplayName("標籤只有 origin 一個：不會因為使用者變多而多出時間序列")
    void onlyOriginTag() {
        adapter.eventsAdded(EventOrigin.ASSISTANT, 1);

        assertEquals(2, registry.find("talkcal.calendar.events.added").counters().size());
        registry.find("talkcal.calendar.events.added").counters()
                .forEach(c -> assertEquals(1, c.getId().getTags().size(), c.getId().toString()));
    }
}
