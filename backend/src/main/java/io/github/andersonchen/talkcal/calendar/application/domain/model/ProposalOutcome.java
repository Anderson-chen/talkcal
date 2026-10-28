package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 使用者對一張提議卡片的表態（#223）：採納（存進行事曆，含改過再存）或拒絕（按取消、移除）。
 * 一張卡片只會有一次表態，先到先算。沒表態（例如直接清空對話）就沒有值。
 */
public enum ProposalOutcome {
    ADOPTED,
    DISMISSED
}
