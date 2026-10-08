package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 找空檔的規則：每一天的某個時段，扣掉有行程的時間，留下夠長的那幾段。
 *
 * 寫成 domain 的 policy（純計算、不碰資料庫），而不是交給模型看著行程清單自己算：
 * 時間區間的加減是小模型最常算錯的東西，而這件事程式算起來既簡單又確定。
 */
public final class FreeSlots {

    private FreeSlots() {
    }

    /**
     * @param range   要找的那幾天
     * @param period  每天的哪個時段
     * @param minimum 至少要多長才算空檔
     * @param busy    這段期間的行程（跨夜、跨天的也算，擋到哪就扣到哪）
     * @param now     現在：已經過去的時間不算空檔 —— 下午三點問「今天下午有空嗎」，不該回答「12:00 有空」
     */
    public static List<FreeSlot> find(DateRange range, DayPeriod period, Duration minimum,
                                      List<CalendarEvent> busy, LocalDateTime now) {
        Objects.requireNonNull(range, "range 不可為 null");
        Objects.requireNonNull(period, "period 不可為 null");
        Objects.requireNonNull(busy, "busy 不可為 null");
        Objects.requireNonNull(now, "now 不可為 null");
        if (minimum == null || minimum.isZero() || minimum.isNegative()) {
            throw new IllegalArgumentException("空檔的最短長度必須是正的：" + minimum);
        }

        List<CalendarEvent> sorted = busy.stream().sorted(Comparator.comparing(CalendarEvent::start)).toList();
        List<FreeSlot> slots = new ArrayList<>();
        for (LocalDate day = range.start(); day.isBefore(range.endExclusive()); day = day.plusDays(1)) {
            LocalDateTime windowStart = day.atTime(period.start());
            LocalDateTime windowEnd = day.atTime(period.end());
            // 從時段開頭（或現在，取比較晚的）往後掃，遇到行程就把游標推到行程結束
            LocalDateTime cursor = windowStart.isBefore(now) ? now : windowStart;
            for (CalendarEvent event : sorted) {
                if (!cursor.isBefore(windowEnd)) {
                    break;
                }
                if (!event.end().isAfter(cursor) || !event.start().isBefore(windowEnd)) {
                    continue; // 這個行程在游標之前就結束了、或在時段之後才開始
                }
                addIfLongEnough(slots, cursor, event.start(), minimum);
                if (event.end().isAfter(cursor)) {
                    cursor = event.end();
                }
            }
            addIfLongEnough(slots, cursor, windowEnd, minimum);
        }
        return List.copyOf(slots);
    }

    private static void addIfLongEnough(List<FreeSlot> slots, LocalDateTime start, LocalDateTime end, Duration minimum) {
        if (end.isAfter(start) && Duration.between(start, end).compareTo(minimum) >= 0) {
            slots.add(new FreeSlot(start, end));
        }
    }
}
