package io.github.andersonchen.talkcal.calendar.adapter.in.assistant;

/**
 * 模型有回話，助理才放棄：回覆是空白，或呼叫工具超過 CalendarAssistant.MAX_STEPS 次還沒回話。
 *
 * 跟模型出錯、資料庫出錯分開，每日次數照樣扣：這種失敗已經花掉好幾次模型呼叫，而且可以用訊息故意觸發。
 *
 * 繼承 IllegalStateException：CalendarAssistant 的契約是失敗丟 IllegalStateException，
 * 沒有另外處理這個型別的呼叫端照樣當成一般失敗（502）。
 */
public final class AssistantGaveUpException extends IllegalStateException {

    AssistantGaveUpException(String message) {
        super(message);
    }
}
