package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 把使用者確認過的行程排進行事曆（inbound port）：「先預覽再確認」的後半段。
 *
 * 收的是 CalendarEvent（domain 型別），不是 ParseEventsUseCase 的結果編號：
 * 使用者可能在預覽畫面上改過時間或標題，所以確認時送回來的是「行程內容」本身。
 * 改過的內容怎麼保證還合規？呼叫端（例如 HTTP controller）要先 new CalendarEvent 才呼叫得了這裡，
 * 而規則就在建構子裡 —— 確認時重驗一次規則，不必多寫一行程式。
 *
 * 一次可以多筆（一句話拆出來的行程是一起確認的），而且全部存或全部不存。
 * 空清單就什麼都不做、回傳空的，不算錯。
 *
 * 存進 owner 的行事曆：之後只有同一個 owner 讀得到、刪得到（#104）。
 *
 * adopting 是這批行程裡「從助理的提議卡片來的」那幾筆所帶的卡片 id（#223），只用來記產品指標，不影響存檔：
 * 核對過是 owner 的、還沒表態過的卡片才算採納，其餘（含沒帶 id 的）都算手動。沒有從卡片來的就是空清單。
 * 一筆行程最多對應一張卡片，所以 id 不會比行程多。
 *
 * 失敗時：
 * - owner 是 null，events、adopting 是 null 或含 null，adopting 比 events 多 → IllegalArgumentException
 * - 資料庫出事 → IllegalStateException（outbound port 的約定），一筆都不會存進去
 */
public interface AddEventsUseCase {

    List<ScheduledEvent> addEvents(OwnerId owner, List<CalendarEvent> events, List<ProposalId> adopting);
}
