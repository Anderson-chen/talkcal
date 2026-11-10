package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.Optional;

/**
 * 用 id 找一個行程（inbound port）。AI 助理要改、要刪某一筆之前，先拿它現在的樣子做成卡片給使用者看。
 *
 * 只找得到 owner 自己的：別人的跟不存在的都是 Optional.empty()（#104）。
 *
 * 失敗時：參數是 null → IllegalArgumentException；資料庫出事 → IllegalStateException。
 */
public interface FindEventUseCase {

    Optional<ScheduledEvent> findEvent(OwnerId owner, EventId id);
}
