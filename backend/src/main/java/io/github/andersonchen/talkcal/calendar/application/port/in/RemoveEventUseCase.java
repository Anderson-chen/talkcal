package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

/**
 * 收 EventId，不收字串：格式錯的在呼叫端轉型時就是 400，不必走到這裡。
 *
 * 拿別人行程的 id 來刪，跟 id 不存在是同一個結果（EventNotFoundException → 404）：
 * 回 403 等於告訴對方「這個 id 存在，只是不是你的」。
 *
 * 失敗時：
 * - owner 是 null、id 是 null → IllegalArgumentException
 * - owner 沒有這個行程（從來沒有、已經刪掉了、或是別人的）→ EventNotFoundException
 * - 資料庫出事 → IllegalStateException
 */
public interface RemoveEventUseCase {

    void removeEvent(OwnerId owner, EventId id);
}
