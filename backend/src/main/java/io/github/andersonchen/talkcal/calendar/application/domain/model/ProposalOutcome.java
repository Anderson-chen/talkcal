package io.github.andersonchen.talkcal.calendar.application.domain.model;

/**
 * 採納是存進行事曆（含改過再存），拒絕是按取消、移除。一張卡片只有一次表態，先到先算；
 * 沒表態（例如直接清空對話）就沒有值。
 */
public enum ProposalOutcome {
    ADOPTED,
    DISMISSED
}
