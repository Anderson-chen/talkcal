package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 帶冪等鍵存行程（outbound port，#51）：同一個擁有者、同一個鍵，只會真的存一次。
 *
 * 為什麼「查有沒有存過」和「存」是同一個方法，不是 use case 先查再存：
 * 兩個重送幾乎同時到（連點兩下、兩個分頁）時，兩邊都會先查到「還沒存過」、然後都存下去。
 * 只有在存的那一刻由資料庫的唯一約束仲裁，才擋得住這種同時發生的重送 —— 那是 adapter 的事，core 只拿到結果。
 *
 * 約定跟 SaveEventsPort 一樣：一批全部存或全部不存；失敗丟 IllegalStateException。
 */
public interface SaveEventsOncePort {

    SaveOnceResult saveOnce(OwnerId owner, IdempotencyKey key, List<ScheduledEvent> events);

    /** 三種結果：真的存了、之前存過（回原本那幾筆）、這個鍵存過的是別的內容。 */
    sealed interface SaveOnceResult {
    }

    /** 第一次看到這個鍵：交給它的那一批存進去了。 */
    record Saved() implements SaveOnceResult {
    }

    /**
     * 這個鍵之前存過同樣的內容：這次什麼都沒存，回的是當時存的那幾筆。
     * 那幾筆後來被刪掉的就不在裡面了（回的是它們現在還在的樣子，不是重新變出來）。
     */
    record AlreadySaved(List<ScheduledEvent> events) implements SaveOnceResult {
    }

    /** 這個鍵之前存的是另一批不同的行程：什麼都沒存。 */
    record KeyUsedForOtherContent() implements SaveOnceResult {
    }
}
