package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * replayed 讓 web adapter 標在回應上（Idempotent-Replayed），呼叫端和查 log 的人才分得出
 * 這次真的新增了，還是之前就存過、只是回傳原本那幾筆。
 */
public record AddedEvents(List<ScheduledEvent> events, boolean replayed) {

    public AddedEvents {
        events = List.copyOf(events);
    }
}
