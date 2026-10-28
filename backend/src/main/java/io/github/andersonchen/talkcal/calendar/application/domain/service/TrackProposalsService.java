package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;

import java.util.Objects;

/**
 * 實作「記下提議卡片的去向」。規則：次數不可為負、拒絕數不可超過 MAX_DISMISSED_PER_CALL；0 就不記。
 */
public final class TrackProposalsService implements TrackProposalsUseCase {

    private final RecordUsagePort usage;

    public TrackProposalsService(RecordUsagePort usage) {
        this.usage = Objects.requireNonNull(usage, "usage 不可為 null");
    }

    @Override
    public void proposalsShown(int count) {
        requireBetween(count, Integer.MAX_VALUE);
        if (count > 0) {
            usage.proposalsShown(count);
        }
    }

    @Override
    public void proposalsDismissed(int count) {
        requireBetween(count, MAX_DISMISSED_PER_CALL);
        if (count > 0) {
            usage.proposalsDismissed(count);
        }
    }

    // 給人看的張數由伺服器自己數，不設上限（max 傳 Integer.MAX_VALUE），只擋負的
    private static void requireBetween(int count, int max) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("卡片數要在 0～" + max + " 之間：" + count);
        }
    }
}
