package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

/**
 * 修改一個已存的行程（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 *
 * 約定：只有 owner 名下、而且每個欄位都還跟 expected 一樣的那一筆才改成 replacement，回 true；
 * 其他情況（不存在、別人的、已經被改過）都不改，回 false。比對和修改要是同一個動作，中間不能讓別人插進來改。
 * 「為什麼沒改到」由 use case 判斷，adapter 只誠實回報。失敗時丟 IllegalStateException。
 */
public interface UpdateEventPort {

    boolean update(OwnerId owner, ScheduledEvent expected, CalendarEvent replacement);
}
