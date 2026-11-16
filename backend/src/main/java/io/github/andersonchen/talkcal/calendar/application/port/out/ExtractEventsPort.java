package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 呼叫哪一家的模型是 adapter 的事。
 *
 * now 由 core 傳進來，不讓 adapter 自己讀時鐘：模型沒有今天的日期，「明天」「下週三」要靠這個值才算得出來，
 * 測試給一個固定的時間，「明天」就是確定的日期。
 *
 * 回傳時已經轉成 CalendarEvent。沒講結束時間的，adapter 用 CalendarEvent.startingAt 建：預設多長是 domain 決定的。
 *
 * 失敗時（連不上、回應讀不懂、模型吐出的時間違反 CalendarEvent 的規則）丟 IllegalStateException。
 * 最後那種也算上游的錯：使用者的描述沒有錯，是模型解析錯了。
 */
public interface ExtractEventsPort {

    List<CalendarEvent> extractEvents(EventDescription description, LocalDateTime now);
}
