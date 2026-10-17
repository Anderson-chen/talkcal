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
        if (checked(count, Integer.MAX_VALUE) > 0) {
            usage.proposalsShown(count);
        }
    }

    @Override
    public void proposalsDismissed(int count) {
        if (checked(count, MAX_DISMISSED_PER_CALL) > 0) {
            usage.proposalsDismissed(count);
        }
    }

    private static int checked(int count, int max) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("卡片數要在 0～" + max + " 之間：" + count);
        }
        return count;
    }
}
