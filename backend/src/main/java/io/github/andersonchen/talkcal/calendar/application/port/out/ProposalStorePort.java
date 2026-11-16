package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalOutcome;

import java.util.List;
import java.util.Set;

/**
 * 發卡片時記下來，表態時核對。
 *
 * 兩件事都是記錄失敗不丟例外：卡片照樣給使用者看、行程照樣存好，只是那幾張不算進指標。實作要自己吞掉例外、留下 log。
 */
public interface ProposalStorePort {

    void saveProposals(OwnerId owner, List<ProposalId> proposals);

    /**
     * 回傳這次算數的張數。只有存在、是 owner 的、還沒表態過的卡片算數；別人的、沒發過的、已經表態過的一律不算，
     * 不分是哪一種，不洩漏別人的卡片存不存在。
     * 「還沒表態過」要在同一個動作裡判斷和寫入：同一張卡片同時被採納和拒絕，只會有一邊算到。記錄失敗時回 0。
     */
    int decide(OwnerId owner, Set<ProposalId> proposals, ProposalOutcome outcome);
}
