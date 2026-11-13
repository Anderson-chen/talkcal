package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** 從歸檔放回 AI 助理對話的月份範圍，兩端都含：對話的歸檔是一個人一個月一包。 */
public record MonthRange(YearMonth from, YearMonth to) {

    // 每個月的包都要讀出來；沒有上限的話，一個請求就能讀完一個人全部的歸檔
    public static final int MAX_MONTHS = 24;

    public MonthRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("起訖月份不可為 null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("起始月份（" + from + "）不可晚於結束月份（" + to + "）");
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAX_MONTHS) {
            throw new IllegalArgumentException("一次最多 " + MAX_MONTHS + " 個月：" + from + " ~ " + to);
        }
    }
}
