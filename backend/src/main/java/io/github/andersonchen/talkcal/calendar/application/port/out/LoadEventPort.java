package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;

import java.util.Optional;

/** id 對、擁有者不對，就是沒有。失敗時丟 IllegalStateException。 */
public interface LoadEventPort {

    Optional<ScheduledEvent> loadOne(OwnerId owner, EventId id);
}
