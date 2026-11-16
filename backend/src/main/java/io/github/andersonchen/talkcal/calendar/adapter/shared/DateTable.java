package io.github.andersonchen.talkcal.calendar.adapter.shared;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 給模型看的日期對照表，例如「2026-10-14 = 下週三」：從這週一起連續三週，今天、明天、後天另外標出來。
 *
 * 把推算日期換成比對字串：關閉 thinking 的 8B 模型心算星期幾不可靠（「下週三」給過星期一、星期二），
 * 在表裡找到「下週三」那一列就不必算。日曆的計算交給 java.time。
 *
 * 放在 adapter.shared：抽行程（adapter.out.extraction）和 AI 助理（adapter.in.assistant）都要放進 prompt，
 * 而 ArchitectureTest 不准 inbound adapter 依賴 outbound adapter。這是寫給這個模型看的日期格式，所以不放進 domain。
 *
 * 一週從星期一開始（台灣的習慣，前端的月曆也是）：星期日說「下週一」指的是明天，表裡也是這樣排。
 */
public final class DateTable {

    // 使用者最遠會說到「下下週」，再遠的通常會講日期
    private static final int WEEKS = 3;
    private static final List<String> WEEK_LABELS = List.of("這週", "下週", "下下週");
    private static final List<String> DAY_LABELS = List.of("今天", "明天", "後天");
    private static final String WEEKDAYS = "一二三四五六日";

    private DateTable() {
    }

    public static String of(LocalDate today) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        StringBuilder table = new StringBuilder();
        for (int i = 0; i < WEEKS * 7; i++) {
            LocalDate date = monday.plusDays(i);
            table.append(date)
                    .append(" = ").append(WEEK_LABELS.get(i / 7)).append(WEEKDAYS.charAt(date.getDayOfWeek().getValue() - 1));
            long daysFromToday = date.toEpochDay() - today.toEpochDay();
            if (daysFromToday >= 0 && daysFromToday < DAY_LABELS.size()) {
                table.append(" = ").append(DAY_LABELS.get((int) daysFromToday));
            }
            table.append('\n');
        }
        // 版面由呼叫端決定
        return table.toString().stripTrailing();
    }
}
