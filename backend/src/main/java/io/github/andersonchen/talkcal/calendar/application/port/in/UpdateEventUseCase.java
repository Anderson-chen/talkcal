package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

/**
 * 把行事曆上的一個行程改成另一個樣子（inbound port）。
 *
 * 除了新的內容，還要帶「改之前看到的樣子」（expected）：資料庫裡的那筆跟它不一樣，就是在你看到之後有人改過了，
 * 這次不改、丟 EventChangedException，不讓晚送的那一邊把先存的改動蓋回去（#50 的同一類問題）。
 *
 * 跟刪除一樣只改得到 owner 自己的行程，別人的跟不存在的同一個結果（#104）。
 *
 * 失敗時：
 * - 參數是 null → IllegalArgumentException
 * - owner 沒有這個行程（從來沒有、已經刪掉了、或是別人的）→ EventNotFoundException
 * - 這筆在 expected 之後被改過了 → EventChangedException
 * - 資料庫出事 → IllegalStateException
 */
public interface UpdateEventUseCase {

    /** 回改好的那筆（同一個 id、新的內容）。 */
    ScheduledEvent updateEvent(OwnerId owner, EventId id, CalendarEvent expected, CalendarEvent replacement);
}
