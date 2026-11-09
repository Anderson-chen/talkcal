package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.ForgetOwnerUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EraseOwnerDataPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;

import java.util.Objects;

/**
 * 實作「忘掉一個擁有者」：資料庫裡的一次刪完，再刪歸檔裡的（#54 把舊行程搬到物件儲存，歸檔不能變成刪不掉的備份）。
 *
 * 先資料庫、後歸檔：歸檔排程只會搬「資料庫裡還有的」，資料庫清空之後就不會再多出新的歸檔。
 * 反過來的話，刪完歸檔、還沒刪資料庫的那一刻排程剛好在跑，又會存進一包。
 * 每一步都可以重跑（刪 0 筆也是成功），中途失敗時重試就會補完。
 */
public final class ForgetOwnerService implements ForgetOwnerUseCase {

    private final EraseOwnerDataPort eraseOwnerDataPort;
    private final EventArchivePort eventArchivePort;

    public ForgetOwnerService(EraseOwnerDataPort eraseOwnerDataPort, EventArchivePort eventArchivePort) {
        this.eraseOwnerDataPort = Objects.requireNonNull(eraseOwnerDataPort, "eraseOwnerDataPort 不可為 null");
        this.eventArchivePort = Objects.requireNonNull(eventArchivePort, "eventArchivePort 不可為 null");
    }

    @Override
    public int forget(OwnerId owner) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        return eraseOwnerDataPort.eraseAll(owner) + eventArchivePort.deleteAll(owner);
    }
}
