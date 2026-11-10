package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;

/**
 * 要改的行程在呼叫端看到之後已經被改過了（另一個分頁、另一張助理卡片先改了）：這次沒有改。
 * HTTP 那頭翻成 409，畫面該重新讀一次、讓使用者看著現在的樣子再決定。
 */
public final class EventChangedException extends RuntimeException {

    public EventChangedException(EventId id) {
        super("這筆行程剛被改過了，請重新整理後再改：" + id);
    }
}
