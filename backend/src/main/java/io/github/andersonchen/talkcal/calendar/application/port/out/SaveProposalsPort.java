package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;

import java.util.List;

/**
 * 記下助理發給 owner 的提議卡片（outbound port，#223），之後才核對得了採納、拒絕。
 *
 * 記錄失敗不丟例外：卡片照樣給使用者看（回覆不該因為指標失敗），只是之後這幾張的採納、拒絕都不算。
 * 實作要自己吞掉例外並留下紀錄。
 */
public interface SaveProposalsPort {

    void saveProposals(OwnerId owner, List<ProposalId> proposals);
}
