package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.util.UUID;

/**
 * 已排進行事曆的行程的身分。
 *
 * 用 UUID，不用資料庫流水號：存檔之前（ScheduledEvent.schedule() 當下）就要有 ID；
 * 流水號也會洩漏總共有幾筆行程，外人猜得到別人的行程編號。
 */
public record EventId(UUID value) {

    public EventId {
        if (value == null) {
            throw new IllegalArgumentException("行程 ID 不可為 null");
        }
    }

    public static EventId newId() {
        return new EventId(UUID.randomUUID());
    }

    /** 格式不對是呼叫端送錯，丟 IllegalArgumentException。 */
    public static EventId of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("行程 ID 不可為 null 或空白");
        }
        try {
            return new EventId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("行程 ID 格式不對：" + text, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
