package application.domain.model;

/**
 * 一次模型呼叫的結果（對話結果）。
 * 目前只有文字；之後可加上完成狀態（被截斷、被拒答）等資訊，這些不一定會記進對話歷史。
 */
public record Reply(String text) {

    // 文字不可為 null 或空白（規則 4）
    public Reply {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("回覆文字不可為 null 或空白");
        }
    }
}
