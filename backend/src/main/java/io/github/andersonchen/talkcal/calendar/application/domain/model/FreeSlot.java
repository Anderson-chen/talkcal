package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;

/** 用牆上時間，理由見 CalendarEvent。 */
public record FreeSlot(LocalDateTime start, LocalDateTime end) {

    public FreeSlot {
        if (start == null || end == null) {
            throw new IllegalArgumentException("空檔的開始與結束都不可為 null");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("空檔的結束（" + end + "）必須晚於開始（" + start + "）");
        }
    }

    public Duration length() {
        return Duration.between(start, end);
    }
}
