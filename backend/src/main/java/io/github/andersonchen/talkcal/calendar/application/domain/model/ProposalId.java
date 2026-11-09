package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.util.UUID;

/**
 * AI 助理發出去的一張提議卡片的身分（#223）。
 *
 * 伺服器發卡片時給，前端在採納（存檔）、拒絕時原樣帶回來，伺服器才核對得了「真的有這張卡片、而且是你的」。
 * 用 UUID 的理由同 EventId：猜不到別人的卡片編號，也不洩漏總共發過幾張。
 */
public record ProposalId(UUID value) {

    public ProposalId {
        if (value == null) {
            throw new IllegalArgumentException("提議 ID 不可為 null");
        }
    }

    public static ProposalId newId() {
        return new ProposalId(UUID.randomUUID());
    }

    /**
     * 從外部（例如 HTTP 請求）收到的字串。格式不對是呼叫端送錯，丟 IllegalArgumentException。
     */
    public static ProposalId of(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("提議 ID 不可為 null 或空白");
        }
        try {
            return new ProposalId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("提議 ID 格式不對：" + text, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
