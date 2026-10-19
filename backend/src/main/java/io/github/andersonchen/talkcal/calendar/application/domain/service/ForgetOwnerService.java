package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.ForgetOwnerUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EraseOwnerDataPort;

import java.util.Objects;

/** 實作「忘掉一個擁有者」：交給 port 一次刪完。 */
public final class ForgetOwnerService implements ForgetOwnerUseCase {

    private final EraseOwnerDataPort eraseOwnerDataPort;

    public ForgetOwnerService(EraseOwnerDataPort eraseOwnerDataPort) {
        this.eraseOwnerDataPort = Objects.requireNonNull(eraseOwnerDataPort, "eraseOwnerDataPort 不可為 null");
    }

    @Override
    public int forget(OwnerId owner) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        return eraseOwnerDataPort.eraseAll(owner);
    }
}
