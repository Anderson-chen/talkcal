package io.github.andersonchen.talkcal.calendar.application.port.out;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/** 契約見 OwnedDataUseCase；實作要在同一個交易裡改完所有存了 owner_id 的表 */
public interface OwnedDataStorePort {

    void transfer(OwnerId from, OwnerId to);

    void deleteAll(OwnerId owner);
}
