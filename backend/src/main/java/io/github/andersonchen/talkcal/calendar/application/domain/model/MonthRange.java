package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * 從歸檔找回資料時的一段月份（#54）：from 到 to，兩端都含。
 *
 * 規則在建構時就檢查，不靠呼叫順序：行程和 AI 助理對話的還原都收這個型別，
 * 哪一邊先呼叫、以後多一個呼叫的地方，都不會有人拿到一段沒檢查過的範圍。
 *
 * 跟 DateRange 分開：月曆一頁是「日期、to 不含」，還原是「月份、兩端都含」，
 * 歸檔本來就一個人一個月一包，用月份講最直接。
 */
public record MonthRange(YearMonth from, YearMonth to) {

    // 一次最多幾個月：每個月的包都要讀出來，範圍沒上限就是讓一個請求讀完一個人全部的歸檔
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

    public boolean contains(YearMonth month) {
        return !month.isBefore(from) && !month.isAfter(to);
    }
}
