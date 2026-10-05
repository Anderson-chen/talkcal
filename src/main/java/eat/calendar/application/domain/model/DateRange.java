package eat.calendar.application.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 一段日期，例如月曆的一頁：從 start 那天的 00:00，到 endExclusive 那天的 00:00（不含）。
 *
 * 結束那天不包含，是刻意的：月曆一頁是 9/28 ～ 11/08，傳 endExclusive = 11/09 就好。
 * 如果結束點包含在內，就得寫成 11/08 23:59:59.999…，然後永遠有人問「23:59:59.5 的行程算不算」。
 * 用不包含的結束點，相鄰的兩段（這頁的結束 = 下頁的開始）剛好接起來，不重疊也沒有縫。
 */
public record DateRange(LocalDate start, LocalDate endExclusive) {

    // 一次最多查幾天。月曆一頁是 6 週 = 42 天，留空間給以後的週檢視、季檢視。
    // 不設上限的話，一個 start = 1900-01-01 的請求就會把整張表讀出來
    static final int MAX_DAYS = 100;

    public DateRange {
        if (start == null || endExclusive == null) {
            throw new IllegalArgumentException("期間的開始與結束都不可為 null");
        }
        if (!endExclusive.isAfter(start)) {
            throw new IllegalArgumentException("期間的結束（" + endExclusive + "）必須晚於開始（" + start + "）");
        }
        long days = ChronoUnit.DAYS.between(start, endExclusive);
        if (days > MAX_DAYS) {
            throw new IllegalArgumentException("一次最多查 " + MAX_DAYS + " 天，這次要了 " + days + " 天");
        }
    }

    /** 期間開始的那一刻（含）。 */
    public LocalDateTime startTime() {
        return start.atStartOfDay();
    }

    /** 期間結束的那一刻（不含）。 */
    public LocalDateTime endTime() {
        return endExclusive.atStartOfDay();
    }
}
