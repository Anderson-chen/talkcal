package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.OwnedDataUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.OwnedDataStorePort;

import java.util.Objects;

/**
 * 先改資料庫、再改歸檔：資料庫改完之後才開始的歸檔排程，不會再把舊擁有者的行程存進舊的前綴；
 * 歸檔那一步失敗時，再呼叫一次只剩歸檔要處理。
 * 排程在資料庫改完之前就讀了的行程，還是會存進舊的前綴，要等那一輪結束後再呼叫一次 transfer 才搬得到。
 */
public final class OwnedDataService implements OwnedDataUseCase {

    private final OwnedDataStorePort store;
    private final EventArchivePort archive;

    public OwnedDataService(OwnedDataStorePort store, EventArchivePort archive) {
        this.store = Objects.requireNonNull(store, "store 不可為 null");
        this.archive = Objects.requireNonNull(archive, "archive 不可為 null");
    }

    @Override
    public void transfer(OwnerId from, OwnerId to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("搬資料的兩個擁有者都不可為 null");
        }
        if (!from.equals(to)) {
            store.transfer(from, to);
            archive.transfer(from, to);
        }
    }

    @Override
    public void deleteAll(OwnerId owner) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        store.deleteAll(owner);
        archive.deleteAll(owner);
    }
}
