package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.OwnedDataUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.OwnedDataStorePort;

import java.util.Objects;

public final class OwnedDataService implements OwnedDataUseCase {

    private final OwnedDataStorePort store;

    public OwnedDataService(OwnedDataStorePort store) {
        this.store = Objects.requireNonNull(store, "store 不可為 null");
    }

    @Override
    public void transfer(OwnerId from, OwnerId to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("搬資料的兩個擁有者都不可為 null");
        }
        if (!from.equals(to)) {
            store.transfer(from, to);
        }
    }

    @Override
    public void deleteAll(OwnerId owner) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        store.deleteAll(owner);
    }
}
