package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 行事曆上的一個行程：什麼事、從幾點到幾點、哪一類、在哪裡、備註。
 *
 * 這是 calendar 模組的第一個 class，後面的解析（自然語言 → 行程）、存檔、HTTP 入口都繞著它轉：
 * 不管行程是 LLM 解析出來的、使用者在預覽畫面上改過的、還是手動表單填的，進到系統之前都要先過這裡的規則。
 *
 * 時間用 LocalDateTime（牆上時間，不帶時區）而不是 Instant：
 * 使用者說的「下午三點」就是台北的牆上三點，單人、單一時區的行事曆存 Instant
 * 只會讓每一層都多一次時區換算。哪天要跨時區，再把型別換掉。
 *
 * 地點和備註是選填，用 Optional 表達「可以沒有」；空白一律當成沒有 ——
 * 表單送來的空字串、LLM 給的 null，進來之後是同一件事，後面的程式不必分兩種情況處理。
 *
 * 刻意還沒有 id：預覽階段的行程還沒存，不該有身分。存檔時再長出來。
 */
public record CalendarEvent(String title, LocalDateTime start, LocalDateTime end,
                            Category category, Optional<String> location, Optional<String> note) {

    // 沒講結束時間時的預設長度。這是業務決定，放在 domain 而不是寫進 prompt 叫模型猜 ——
    // 換一顆模型、換一家供應商，「沒講就算一小時」都不會跟著變
    static final Duration DEFAULT_DURATION = Duration.ofHours(1);

    public CalendarEvent {
        // 空標題在月曆格上就是一格什麼都看不到的行程；
        // LLM 解析失敗時最常見的爛結果正是空字串，在這裡擋下
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("行程標題不可為 null 或空白");
        }
        if (start == null) {
            throw new IllegalArgumentException("行程開始時間不可為 null");
        }
        if (end == null) {
            throw new IllegalArgumentException("行程結束時間不可為 null");
        }
        // 「晚上十點到凌晨一點」很容易被算成同一天的 01:00，變成倒著走的行程。
        // 等於也不行：零長度的行程在月曆上沒有意義
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("行程結束時間（" + end + "）必須晚於開始時間（" + start + "）");
        }
        if (category == null) {
            // 「沒指定」要明說成 Category.DEFAULT，不收 null：null 分不出是「沒指定」還是「忘了傳」
            throw new IllegalArgumentException("行程分類不可為 null（沒有指定就用 Category.DEFAULT）");
        }
        location = optionalText(location, "地點");
        note = optionalText(note, "備註");
    }

    /**
     * 只有標題和時間的行程：分類用預設、沒有地點和備註。
     * 加分類之前的程式碼（和測試）都是這樣建行程的，意思不變。
     */
    public CalendarEvent(String title, LocalDateTime start, LocalDateTime end) {
        this(title, start, end, Category.DEFAULT, Optional.empty(), Optional.empty());
    }

    /**
     * 只知道開始時間的行程，結束時間用預設長度補上。
     * 「明天三點跟小明吃飯」沒講要多久，是自然語言裡的常態。
     */
    public static CalendarEvent startingAt(String title, LocalDateTime start) {
        if (start == null) {
            // 先擋：不然 start.plus 會先丟 NullPointerException，訊息看不出是哪條規則
            throw new IllegalArgumentException("行程開始時間不可為 null");
        }
        return new CalendarEvent(title, start, start.plus(DEFAULT_DURATION));
    }

    /** 同一個行程，換一個分類。LLM 解析和手動表單都是「先有時間、再補細節」，這樣寫比較順。 */
    public CalendarEvent withCategory(Category category) {
        return new CalendarEvent(title, start, end, category, location, note);
    }

    /** 同一個行程，換上地點與備註（null 或空白就是沒有）。 */
    public CalendarEvent withDetails(String location, String note) {
        return new CalendarEvent(title, start, end, category, Optional.ofNullable(location), Optional.ofNullable(note));
    }

    // 選填文字：Optional 本身不可為 null（那是呼叫端違約）；裡面是空白就當成沒有，有字就去掉前後空白
    private static Optional<String> optionalText(Optional<String> text, String what) {
        Objects.requireNonNull(text, what + "要用 Optional.empty() 表示沒有，不可傳 null");
        return text.map(String::strip).filter(s -> !s.isEmpty());
    }
}
