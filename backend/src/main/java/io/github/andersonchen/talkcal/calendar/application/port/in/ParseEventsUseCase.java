package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;

import java.util.List;

/**
 * 把自然語言解析成行程（inbound port）：給使用者預覽，不存檔。
 *
 * 只解析、不存，是「先預覽再確認」這個流程的前半段：模型把日期算錯時，
 * 使用者在確認之前就看得到、改得了，錯的行程不會直接變成資料庫裡的髒資料。
 * 存檔是另一個 use case。
 *
 * 回傳一串：一句話裡講了幾個行程就拆成幾筆。
 * 一筆都沒有（「今天天氣真好」）是正常結果、回傳空的，不算失敗 —— 怎麼跟使用者說是畫面的事。
 *
 * 失敗時：
 * - 描述空白 → IllegalArgumentException
 * - 模型出事、或吐出不合規則的行程 → IllegalStateException（outbound port 的約定）
 */
public interface ParseEventsUseCase {

    List<CalendarEvent> parseEvents(String description);
}
