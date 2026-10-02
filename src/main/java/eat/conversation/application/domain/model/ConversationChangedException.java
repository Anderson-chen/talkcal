package eat.conversation.application.domain.model;

/**
 * 存檔時發現：從讀出這段對話到現在，已經有別的請求先存過了。
 *
 * 典型情境是同一段對話被同時問了兩題 —— 兩個請求讀到同一版、各自等 LLM 十幾秒，
 * 先回來的存成功，後回來的就是這個例外。後回來的那一題沒有被記下，使用者重問就好。
 *
 * 放在 model 而不是 port.out：存檔的 adapter 丟它、HTTP 的 adapter 要把它翻成 409，
 * 兩邊都該依賴核心的型別，而不是 inbound adapter 去認識 outbound port。
 *
 * 獨立成一個型別，而不是借用 IllegalStateException：那個在這個專案代表「上游服務出事」（對應 502），
 * 而這是「你的對話剛被改過」，呼叫端該做的事（重新整理再問）完全不同。
 */
public final class ConversationChangedException extends RuntimeException {

    public ConversationChangedException(ConversationId id) {
        super("對話 " + id + " 在這段時間被別的請求更新了，這一題沒有存下來，請重新整理後再問一次");
    }
}
