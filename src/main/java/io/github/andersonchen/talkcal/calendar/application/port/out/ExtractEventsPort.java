package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 從自然語言裡抽出行程（outbound port）：由核心定義、由 Adapter（llama.cpp、OpenAI、Claude）實作。
 *
 * now 由呼叫端給，不讓 adapter 自己讀時鐘：
 * 模型不知道今天幾號，「明天」「下週三」都要靠這個值才算得出來；
 * 而由 core 傳進來，測試給一個固定的時間，「明天」就是一個確定的日期。
 *
 * 回傳的已經是 domain 的 CalendarEvent：協定上長什麼樣（JSON 欄位、沒講結束時間時怎麼表示）
 * 是 adapter 的事，翻譯好才交回來。沒講結束時間的，adapter 用 CalendarEvent.startingAt 建 ——
 * 預設多長是 domain 決定的，adapter 只負責挑對建構方式。
 *
 * 失敗時（連不上、回應讀不懂、模型吐出的時間違反 CalendarEvent 的規則）丟 IllegalStateException。
 * 最後那種也算上游的錯：使用者的描述沒有錯，是模型解析錯了。
 */
public interface ExtractEventsPort {

    List<CalendarEvent> extractEvents(EventDescription description, LocalDateTime now);
}
