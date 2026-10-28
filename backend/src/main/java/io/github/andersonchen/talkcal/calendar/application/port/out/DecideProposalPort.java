package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;

/**
 * 記下 owner 對一張提議卡片的表態（outbound port，#223）。
 *
 * 回 true 只有一種情況：這張卡片存在、是 owner 的、而且還沒表態過 —— 這次的表態才算數。
 * 別人的卡片、沒發過的 id、已經表態過的，一律回 false，不分是哪一種（不洩漏別人的卡片存不存在）。
 * 「還沒表態過」要在同一個動作裡判斷和寫入：同一張卡片同時被採納和拒絕，只會有一個回 true。
 *
 * 記錄失敗不丟例外、回 false：採納時行程已經存好了，不能因為指標記不下來讓整個請求失敗。
 */
public interface DecideProposalPort {

    boolean decide(OwnerId owner, ProposalId proposal, ProposalOutcome outcome);
}
