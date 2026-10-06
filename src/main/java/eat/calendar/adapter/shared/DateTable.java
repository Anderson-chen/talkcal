package eat.calendar.adapter.shared;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * 給模型看的日期對照表：「2026-10-14 = 下週三」，從這週一起連續三週，今天 / 明天 / 後天另外標出來。
 *
 * 這張表把「推算日期」換成「比對字串」：不 thinking 的 8B 模型心算星期幾不可靠（「下週三」給過星期一、星期二），
 * 在表裡找到「下週三」那一列就不必算。日曆的算術交給 java.time。
 *
 * 放在 adapter.shared 而不是某一個 adapter 裡：抽行程（adapter.out.extraction）和 AI 助理（adapter.in.assistant）
 * 都要把它放進 prompt，而 inbound adapter 不准依賴 outbound adapter（ArchitectureTest）。
 * 它是「怎麼跟這顆模型講日期」的知識，所以還是在 adapter 這一圈，不進 domain。
 *
 * 一週從星期一開始（台灣的習慣，前端的月曆也是）：星期日說「下週一」指的是明天，表裡也剛好是這樣排。
 */
public final class DateTable {

    // 涵蓋幾週。使用者最遠會說到「下下週」，再遠的通常會講日期
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
        // 最後一個換行拿掉，讓呼叫端的版面自己決定
        return table.toString().stripTrailing();
    }
}
