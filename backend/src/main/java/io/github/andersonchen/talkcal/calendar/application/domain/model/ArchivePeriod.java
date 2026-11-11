package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 查看封存時的一段日期（#274）：from 到 to，兩端都含，只看一天就是 from = to。
 *
 * 跟 DateRange 分開：月曆一頁是「to 不含、最多 100 天」，這裡是使用者自己選的日期，選一天時兩端同一天比較直覺。
 * 上限跟 MonthRange 一樣 24 個月：每多一個月就要多讀幾包物件儲存。
 *
 * 「在這段日期裡」跟月曆同一個定義（LoadEventsPort）：跟這段時間有重疊就算。
 */
public record ArchivePeriod(LocalDate from, LocalDate to) {

    /**
     * 歸檔依行程「結束」的月份分包（EventArchivePort），範圍內開始、範圍之後才結束的行程放在後面月份的包裡，
     * 所以往後多讀一個月：跟範圍重疊、長度不超過 28 天的行程，不管從哪天開始都找得到。
     * 結束在 to 的下下個月以後的長行程找不到：每天的排程各存一包，一個人一個月可能有二三十包，
     * 多讀一個月就多二三十次讀取，為了很少見的長行程不划算。
     */
    static final int MONTHS_AFTER = 1;

    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    public ArchivePeriod {
        if (from == null || to == null) {
            throw new IllegalArgumentException("起訖日期不可為 null");
        }
        // 遠到年份超過 4 位數的日期：to 隔天、下個月會超出 LocalDate 的範圍，在這裡先擋成 400，不要後面才變成 500
        if (to.isAfter(LATEST)) {
            throw new IllegalArgumentException("結束日期太遠了：" + to);
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("開始日期（" + from + "）不可晚於結束日期（" + to + "）");
        }
        // 最多幾個月的規則只寫在 MonthRange，超過的話它丟 IllegalArgumentException
        new MonthRange(YearMonth.from(from), YearMonth.from(to));
    }

    /** 跟這段日期有重疊：開始早於 to 隔天的 00:00，而且結束晚於 from 的 00:00。剛好在 from 00:00 結束的不算。 */
    public boolean overlaps(CalendarEvent event) {
        return event.start().isBefore(endTime()) && event.end().isAfter(startTime());
    }

    /** 要讀的第一個月的包（依行程結束的月份）：from 那個月，再早結束的不可能跟範圍重疊。 */
    public YearMonth firstEndMonth() {
        return YearMonth.from(from);
    }

    /** 要讀的最後一個月的包：to 的下一個月（見 MONTHS_AFTER）。 */
    public YearMonth lastEndMonth() {
        return YearMonth.from(to).plusMonths(MONTHS_AFTER);
    }

    private LocalDateTime startTime() {
        return from.atStartOfDay();
    }

    private LocalDateTime endTime() {
        return to.plusDays(1).atStartOfDay();
    }
}
