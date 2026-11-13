package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;

/**
 * HTTP 那頭翻成 404。
 *
 * 刪第二次也丟這個，不靜靜成功：畫面上按了刪除卻早就沒了（例如另一個分頁先刪了），使用者該知道畫面已經過時。
 */
public final class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(EventId id) {
        super("找不到行程：" + id);
    }
}
