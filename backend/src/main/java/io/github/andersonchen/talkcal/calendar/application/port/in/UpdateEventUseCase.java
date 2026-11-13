package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

/**
 * 除了新的內容，還要帶改之前看到的樣子（expected）：資料庫裡的那筆跟它不一樣，就是你看到之後有人改過了，
 * 這次不改、丟 EventChangedException，不讓晚送的一邊把先存的改動蓋回去。
 *
 * 別人的行程跟不存在的同一個結果，理由見 RemoveEventUseCase。
 *
 * 失敗時：
 * - 參數是 null → IllegalArgumentException
 * - owner 沒有這個行程（從來沒有、已經刪掉了、或是別人的）→ EventNotFoundException
 * - 這筆在 expected 之後被改過了 → EventChangedException
 * - 資料庫出事 → IllegalStateException
 */
public interface UpdateEventUseCase {

    ScheduledEvent updateEvent(OwnerId owner, EventId id, CalendarEvent expected, CalendarEvent replacement);
}
