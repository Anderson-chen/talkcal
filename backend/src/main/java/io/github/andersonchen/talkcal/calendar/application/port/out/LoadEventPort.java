package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.Optional;

/**
 * 用 id 讀一個行程（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 * 只讀 owner 的那一筆：id 對、擁有者不對，就是沒有。失敗時丟 IllegalStateException。
 */
public interface LoadEventPort {

    Optional<ScheduledEvent> loadOne(OwnerId owner, EventId id);
}
