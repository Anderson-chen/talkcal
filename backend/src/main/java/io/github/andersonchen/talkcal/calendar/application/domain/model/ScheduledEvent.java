package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 已經排進行事曆的行程。
 *
 * 用組合包住 CalendarEvent，不在 CalendarEvent 加 id：預覽階段（解析出來、使用者還沒確認）的行程不該有身分，
 * 確認之後排進去的那一刻才發 id。兩個階段兩個型別，存檔只收 ScheduledEvent，預覽的行程根本傳不進去。
 */
public record ScheduledEvent(EventId id, CalendarEvent event) {

    public ScheduledEvent {
        if (id == null) {
            throw new IllegalArgumentException("已排入的行程一定要有 ID");
        }
        if (event == null) {
            throw new IllegalArgumentException("行程不可為 null");
        }
    }

    /** 整個模組唯一發 id 的地方。 */
    public static ScheduledEvent schedule(CalendarEvent event) {
        return new ScheduledEvent(EventId.newId(), event);
    }
}
