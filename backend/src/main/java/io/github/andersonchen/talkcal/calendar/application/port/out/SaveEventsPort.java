package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.List;

/**
 * 約定：一次給的那幾筆全部存或全部不存。一句話拆出來的行程是使用者一起確認的，存了一半，
 * 使用者會看到少了一筆卻不知道為什麼。
 *
 * 擁有者跟著這一批傳，不放進 ScheduledEvent：一批行程一定屬於發請求的那個人，domain 的規則跟是誰的無關。
 * 失敗時丟 IllegalStateException。
 */
public interface SaveEventsPort {

    void save(OwnerId owner, List<ScheduledEvent> events);
}
