package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 「先預覽再確認」的後半段：把使用者確認過的行程存進 owner 的行事曆。
 *
 * 收 CalendarEvent，不收預覽結果的編號：使用者可能在預覽畫面改過時間或標題，確認時送回來的是行程內容本身。
 * 呼叫端得先 new CalendarEvent 才呼叫得了這裡，改過的內容就在建構子裡重驗一次規則。
 *
 * 一句話拆出來的幾筆是一起確認的，全部存或全部不存。空清單什麼都不做、回傳空的。
 *
 * adopting 是這批裡從助理卡片來的那幾筆帶的卡片 id，只用來記產品指標、不影響存檔：
 * 核對過是 owner 的、還沒表態過的才算採納，其餘（含沒帶 id 的）算手動。一筆行程最多對應一張卡片。
 *
 * 失敗時：
 * - owner 是 null，events、adopting 是 null 或含 null，adopting 比 events 多 → IllegalArgumentException
 * - 資料庫出事 → IllegalStateException，一筆都不會存進去
 */
public interface AddEventsUseCase {

    List<ScheduledEvent> addEvents(OwnerId owner, List<CalendarEvent> events, List<ProposalId> adopting);

    /**
     * 帶冪等鍵：同一個 owner 用同一個鍵再送一次同樣的行程，不會多存，回的是第一次存的那幾筆，也不重複記採納和用量。
     *
     * 按了「加入」之後後端存好了、回應卻沒送到（逾時、502、斷線），畫面只看到失敗，使用者會再按一次；
     * 沒有鍵的話，後端分不出這是重送還是真的要再加一筆一樣的，只能照存。
     * 卡片的 proposalId 擋不了這件事：proposalId 只用來記指標（記不下就算了），手動表單也沒有卡片。
     *
     * 額外的失敗：
     * - 同一個鍵之前存的是不同的行程 → IdempotencyKeyReusedException（什麼都不存）
     * - key 是 null → IllegalArgumentException
     */
    AddedEvents addEvents(OwnerId owner, IdempotencyKey key, List<CalendarEvent> events, List<ProposalId> adopting);
}
