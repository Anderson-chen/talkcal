package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 同一個擁有者、同一個鍵，只會真的存一次。
 *
 * 「存過沒有」和「存」是同一個方法，不讓 use case 先查再存：兩個重送幾乎同時到（連點兩下、兩個分頁）時，
 * 兩邊都會先查到還沒存過、然後都存下去。只有存的那一刻由資料庫的唯一約束仲裁才擋得住。
 *
 * 約定：一批全部存或全部不存（理由見 SaveEventsPort）；失敗丟 IllegalStateException。
 */
public interface SaveEventsOncePort {

    SaveOnceResult saveOnce(OwnerId owner, IdempotencyKey key, List<ScheduledEvent> events);

    sealed interface SaveOnceResult {
    }

    record Saved() implements SaveOnceResult {
    }

    /**
     * 這個鍵之前存過同樣的內容：這次什麼都沒存，回的是當時存的那幾筆現在的樣子，後來被刪掉的就不在裡面。
     */
    record AlreadySaved(List<ScheduledEvent> events) implements SaveOnceResult {
    }

    /** 這個鍵之前存的是另一批不同的行程：什麼都沒存。 */
    record KeyUsedForOtherContent() implements SaveOnceResult {
    }
}
