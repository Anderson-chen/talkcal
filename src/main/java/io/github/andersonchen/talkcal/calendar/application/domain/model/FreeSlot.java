package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 一段沒有行程的時間。跟 CalendarEvent 一樣用牆上時間，規則也一樣：結束要晚於開始。
 */
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
