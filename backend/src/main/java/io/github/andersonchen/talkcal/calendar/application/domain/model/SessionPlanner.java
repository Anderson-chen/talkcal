package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 使用者說「幫我排兩天讀書、各兩小時」時，決定幾點到幾點。純計算，不交給模型挑，理由見 CalendarTools 的 plan_events。
 *
 * 每天一段，放在當天最早放得下的空檔，開始時間進位到整點或半點。
 * 不做一天好幾段：模型會把「各排兩小時」填成每天兩段，也還沒有人要過。
 * 不猜偏好（例如讀書要排下午），只求放得下、看起來整齊：排得不合意，使用者在卡片上按「自己調整」改。
 */
public final class SessionPlanner {

    private SessionPlanner() {
    }

    /**
     * @param free FreeSlots.find 的結果：照時間排好，不跨天
     * @return 照時間排；空檔都放不下的那天就不排
     */
    public static List<FreeSlot> plan(List<FreeSlot> free, Duration length) {
        Objects.requireNonNull(free, "free 不可為 null");
        if (length == null || length.isZero() || length.isNegative()) {
            throw new IllegalArgumentException("每段的長度必須是正的：" + length);
        }

        // 空檔照時間排好，所以只要記上一段排在哪天
        LocalDate lastPlanned = null;
        List<FreeSlot> sessions = new ArrayList<>();
        for (FreeSlot slot : free) {
            LocalDate day = slot.start().toLocalDate();
            LocalDateTime start = roundUpToHalfHour(slot.start());
            if (!day.equals(lastPlanned) && !start.plus(length).isAfter(slot.end())) {
                sessions.add(new FreeSlot(start, start.plus(length)));
                lastPlanned = day;
            }
        }
        return List.copyOf(sessions);
    }

    // 11:37 → 12:00、10:00 → 10:00：空檔從「現在」開始時，卡片上不要出現 11:37 這種時間
    static LocalDateTime roundUpToHalfHour(LocalDateTime time) {
        LocalDateTime floor = time.truncatedTo(ChronoUnit.HOURS).plusMinutes(time.getMinute() >= 30 ? 30 : 0);
        return floor.equals(time) ? time : floor.plusMinutes(30);
    }
}
