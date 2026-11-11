package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 在空檔裡排時段（#261）：使用者說「幫我排兩天讀書、各兩小時」，由這裡決定幾點到幾點。
 *
 * 跟 FreeSlots 一樣寫成純計算，不交給模型挑：qwen3-8b 要「查空檔、挑時段、呼叫工具」連做三步，
 * 實測常停在第二步，用文字列出時段再問要不要新增；一直反問、出不了卡片。挑時段程式做起來簡單又確定。
 *
 * 規則：每天一段，放在當天最早放得下的空檔，開始時間進位到整點或半點。
 * 不做一天好幾段：模型會把「各排兩小時」填成每天兩段（實測），也還沒有人要過。
 * 排得不合意，使用者在卡片上按「自己調整」改，所以這裡不猜偏好（例如讀書要排下午），只求放得下、看起來整齊。
 */
public final class SessionPlanner {

    private SessionPlanner() {
    }

    /**
     * @param free   空檔（FreeSlots.find 的結果：照時間排好，不跨天）
     * @param length 每段多長
     * @return 排好的時段，照時間排；空檔都放不下的天就不排
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
