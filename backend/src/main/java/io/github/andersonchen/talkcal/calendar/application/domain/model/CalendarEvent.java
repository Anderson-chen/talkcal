package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 不管是 LLM 解析出來的、使用者在預覽畫面改過的，還是手動表單填的行程，都要先過這裡的規則才進得了系統。
 *
 * 時間用 LocalDateTime（牆上時間），不用 Instant：使用者說的「下午三點」就是台北的牆上三點，
 * 單一時區的行事曆存 Instant 只會讓每一層多一次時區換算（見 ADR 0006）。
 *
 * 地點、備註空白一律當成沒有：表單送來的空字串、LLM 給的 null，進來之後是同一件事。
 *
 * 沒有 id，理由見 ScheduledEvent。
 */
public record CalendarEvent(String title, LocalDateTime start, LocalDateTime end,
                            Category category, Optional<String> location, Optional<String> note) {

    // 業務決定，放在 domain，不寫進 prompt 讓模型猜：換一個模型、換一家供應商，「沒講就算一小時」都不會跟著變
    static final Duration DEFAULT_DURATION = Duration.ofHours(1);

    public CalendarEvent {
        // 空標題在月曆上是一格什麼都看不到的行程，也是 LLM 解析失敗時最常見的結果
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("行程標題不可為 null 或空白");
        }
        if (start == null) {
            throw new IllegalArgumentException("行程開始時間不可為 null");
        }
        if (end == null) {
            throw new IllegalArgumentException("行程結束時間不可為 null");
        }
        // 「晚上十點到凌晨一點」很容易被算成同一天的 01:00，變成倒著走的行程。零長度的行程在月曆上也沒有意義
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("行程結束時間（" + end + "）必須晚於開始時間（" + start + "）");
        }
        if (category == null) {
            // null 分不出是「沒指定」還是「忘了傳」，沒指定要明說成 Category.DEFAULT
            throw new IllegalArgumentException("行程分類不可為 null（沒有指定就用 Category.DEFAULT）");
        }
        location = optionalText(location, "地點");
        note = optionalText(note, "備註");
    }

    public CalendarEvent(String title, LocalDateTime start, LocalDateTime end) {
        this(title, start, end, Category.DEFAULT, Optional.empty(), Optional.empty());
    }

    public static CalendarEvent startingAt(String title, LocalDateTime start) {
        if (start == null) {
            // 先擋：不然 start.plus 會先丟 NullPointerException，訊息看不出是哪條規則
            throw new IllegalArgumentException("行程開始時間不可為 null");
        }
        return new CalendarEvent(title, start, start.plus(DEFAULT_DURATION));
    }

    public CalendarEvent withCategory(Category category) {
        return new CalendarEvent(title, start, end, category, location, note);
    }

    /** null 或空白就是沒有。 */
    public CalendarEvent withDetails(String location, String note) {
        return new CalendarEvent(title, start, end, category, Optional.ofNullable(location), Optional.ofNullable(note));
    }

    // Optional 本身是 null 是呼叫端違約；裡面是空白就當成沒有，有字就去掉前後空白
    private static Optional<String> optionalText(Optional<String> text, String what) {
        Objects.requireNonNull(text, what + "要用 Optional.empty() 表示沒有，不可傳 null");
        return text.map(String::strip).filter(s -> !s.isEmpty());
    }
}
