package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.util.UUID;

/**
 * 一個已排進行事曆的行程的身分。
 *
 * 跟 conversation 的 ConversationId 同一套理由（兩個模組不共用類別，所以各寫一份）：
 * - 包成自己的型別：方法簽章上看得出「這裡要的是行程的 ID」，不會跟別種 UUID 傳錯
 * - 用 UUID 不用資料庫流水號：ID 在 domain 裡、存檔之前就要有（ScheduledEvent.schedule() 當下）；
 *   流水號也會洩漏「總共有幾筆行程」，外人猜得到別人的行程編號
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

    /**
     * 從外部（例如 HTTP 請求）收到的字串。格式不對是呼叫端送錯，丟 IllegalArgumentException。
     */
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
