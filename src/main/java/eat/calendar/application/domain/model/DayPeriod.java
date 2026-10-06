package eat.calendar.application.domain.model;

import java.time.LocalTime;

/**
 * 一天裡的時段：問「哪天下午有空」時，「下午」是幾點到幾點。
 *
 * 由程式定義、不交給模型自己說：實測時模型把 10:00 開始的空檔也叫成「下午」。
 * 深夜、凌晨刻意不在任何時段裡 —— 沒有人問「什麼時候有空」是在找半夜三點。
 */
public enum DayPeriod {
    MORNING(LocalTime.of(8, 0), LocalTime.NOON),
    AFTERNOON(LocalTime.NOON, LocalTime.of(18, 0)),
    EVENING(LocalTime.of(18, 0), LocalTime.of(22, 0)),
    // 沒講時段（「這週哪天有空」）：醒著、可以排事情的時間
    ANYTIME(LocalTime.of(8, 0), LocalTime.of(22, 0));

    private final LocalTime start;
    private final LocalTime end;

    DayPeriod(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public LocalTime start() {
        return start;
    }

    public LocalTime end() {
        return end;
    }
}
