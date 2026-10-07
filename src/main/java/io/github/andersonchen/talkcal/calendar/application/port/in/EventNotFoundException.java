package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;

/**
 * 指定的行程不存在：
 * 是 use case 回答呼叫端的一種結果，所以放在 port.in，HTTP 那頭翻成 404。
 *
 * 刪第二次也是這個例外，不是靜悄悄成功：
 * 畫面上按了刪除卻發現早就沒了（例如另一個分頁先刪了），使用者該知道畫面上的東西已經過時。
 */
public final class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(EventId id) {
        super("找不到行程：" + id);
    }
}
