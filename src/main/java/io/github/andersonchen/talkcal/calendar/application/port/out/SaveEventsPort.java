package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 儲存已排入的行程（outbound port）：由核心定義、由 Adapter（PostgreSQL…）實作。
 *
 * 約定：一次給的那幾筆，全部存或全部不存。
 * 一句話拆出來的行程是使用者一起確認的；存了一半，使用者會看到少了一筆卻不知道為什麼。
 * 怎麼做到（交易）是 adapter 的事，core 不知道有交易這回事。
 *
 * 只收 ScheduledEvent：沒有身分的預覽行程在型別上就傳不進來。
 * 失敗時丟 IllegalStateException。
 */
public interface SaveEventsPort {

    void save(List<ScheduledEvent> events);
}
