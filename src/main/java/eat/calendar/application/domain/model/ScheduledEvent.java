package eat.calendar.application.domain.model;

/**
 * 已經排進行事曆的行程：一個身分，加上行程本身。
 *
 * 用組合包住 CalendarEvent，而不是在 CalendarEvent 上加 id 欄位：
 * 預覽階段（解析出來、使用者還沒確認）的行程不該有身分，CalendarEvent 刻意沒有 id；
 * 確認之後才「排進去」，排進去的那一刻才發 id。兩個階段用兩個型別，
 * 方法簽章就說得出收的是哪一種 —— 存檔只收 ScheduledEvent，預覽的行程根本傳不進去。
 *
 * 規則全在 CalendarEvent（標題、起訖），這裡不重複；這裡只多一條：一定要有身分。
 */
public record ScheduledEvent(EventId id, CalendarEvent event) {

    public ScheduledEvent {
        if (id == null) {
            throw new IllegalArgumentException("已排入的行程一定要有 ID");
        }
        if (event == null) {
            throw new IllegalArgumentException("行程不可為 null");
        }
    }

    /**
     * 把確認過的行程排進行事曆，發一個新的身分。
     * 這是整個模組唯一發 id 的地方，對應 conversation 的 Conversation.start()。
     */
    public static ScheduledEvent schedule(CalendarEvent event) {
        return new ScheduledEvent(EventId.newId(), event);
    }
}
