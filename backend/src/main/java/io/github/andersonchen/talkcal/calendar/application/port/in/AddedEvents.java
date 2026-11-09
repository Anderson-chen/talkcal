package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 帶冪等鍵新增的結果：存了哪幾筆，以及這次是不是重送（#51）。
 *
 * replayed 給 HTTP 那頭標在回應上（Idempotent-Replayed），呼叫端和查 log 的人才分得出
 * 「這次真的新增了」還是「之前就存過、這次只是把原本那幾筆回給你」。
 */
public record AddedEvents(List<ScheduledEvent> events, boolean replayed) {

    public AddedEvents {
        events = List.copyOf(events);
    }
}
