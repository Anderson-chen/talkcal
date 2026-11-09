package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;

import java.util.List;
import java.util.Set;

/**
 * 助理發出去的提議卡片存在哪（outbound port，#223）：發的時候記下來，表態的時候核對。
 *
 * 兩件事都「記錄失敗不丟例外」：卡片照樣給使用者看、行程照樣存好，只是那幾張的採納、拒絕不算進指標。
 * 實作要自己吞掉例外並留下紀錄。
 */
public interface ProposalStorePort {

    /** 記下發給 owner 的這幾張卡片。 */
    void saveProposals(OwnerId owner, List<ProposalId> proposals);

    /**
     * owner 對這幾張卡片表態，回傳這次算數的張數。
     *
     * 只有「存在、是 owner 的、還沒表態過」的卡片算數；別人的、沒發過的、已經表態過的一律不算，不分是哪一種
     * （不洩漏別人的卡片存不存在）。「還沒表態過」要在同一個動作裡判斷和寫入：同一張卡片同時被採納和拒絕，只會有一邊算到。
     * 記錄失敗時回 0。
     */
    int decide(OwnerId owner, Set<ProposalId> proposals, ProposalOutcome outcome);
}
