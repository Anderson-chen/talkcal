package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteArchivedEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEndedEventsPort;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 實作「把舊行程搬到歸檔」（#54）。
 *
 * 順序是整件事的重點：先存進歸檔 → 存成功了 → 才刪資料庫。反過來（先刪再存）只要存的那一步失敗，資料就沒了。
 * 先存再刪最壞的情況是「存好了、刪之前當掉」：資料兩邊都有一份，下次排程再存一包、再刪一次 ——
 * 歸檔裡多一份重複的，load 時依 id 去重，不會變成兩筆。重複比遺失便宜。
 *
 * 一個人、一個月一包（依行程結束的月份）：
 * - 依人分：還原、刪帳號（#52）都是「某個人的」，一包只有一個人的資料，讀和刪都不必打開來挑
 * - 依月分：還原通常是「某段時間」，只讀那幾個月的包
 * - 一包失敗只影響那一包：每包各自「存好才刪」，沒存好的那包留在資料庫，下次再試
 *
 * 一次讀 batchSize 筆、搬完再讀下一批，直到沒有舊的了：資料再多記憶體也只放一批。
 * 有某包失敗就停：再讀一次只會又讀到同一批（它們還在資料庫裡），變成無窮迴圈。
 * 對話那邊（app.job.AssistantMemoryArchive）是同一個迴圈、同一組停止條件，改一邊要對照另一邊：
 * 兩邊在不同層（這裡是 core，對話不在 core），共用一個 helper 要讓 core 認識 app.job，所以各寫一份。
 */
public final class ArchiveEventsService implements ArchiveEventsUseCase {

    private final LoadEndedEventsPort loadEndedEvents;
    private final EventArchivePort archive;
    private final DeleteArchivedEventsPort deleteArchived;
    private final int batchSize;

    public ArchiveEventsService(LoadEndedEventsPort loadEndedEvents, EventArchivePort archive,
                                DeleteArchivedEventsPort deleteArchived, int batchSize) {
        this.loadEndedEvents = Objects.requireNonNull(loadEndedEvents, "loadEndedEvents 不可為 null");
        this.archive = Objects.requireNonNull(archive, "archive 不可為 null");
        this.deleteArchived = Objects.requireNonNull(deleteArchived, "deleteArchived 不可為 null");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize 要大於 0：" + batchSize);
        }
        this.batchSize = batchSize;
    }

    @Override
    public Report archiveEndedBefore(LocalDateTime cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff 不可為 null");
        }
        int archived = 0;
        int failedGroups = 0;
        while (true) {
            Map<OwnerId, List<ScheduledEvent>> batch = loadEndedEvents.loadEndedBefore(cutoff, batchSize);
            int loaded = batch.values().stream().mapToInt(List::size).sum();
            int deletedThisBatch = 0;
            for (Map.Entry<OwnerId, List<ScheduledEvent>> owner : batch.entrySet()) {
                for (Map.Entry<YearMonth, List<ScheduledEvent>> month : byEndMonth(owner.getValue()).entrySet()) {
                    try {
                        archive.store(owner.getKey(), month.getKey(), month.getValue());
                    } catch (IllegalStateException e) {
                        // 沒存好：這一包一筆都不刪，留在資料庫裡等下次
                        failedGroups++;
                        continue;
                    }
                    List<EventId> ids = month.getValue().stream().map(ScheduledEvent::id).toList();
                    deletedThisBatch += deleteArchived.deleteArchived(owner.getKey(), ids, cutoff);
                }
            }
            archived += deletedThisBatch;
            // 讀到的不滿一批 = 舊的都讀完了；有包失敗、或一筆都沒刪到（全被改成未來的）= 再讀也是同一批
            if (loaded < batchSize || failedGroups > 0 || deletedThisBatch == 0) {
                return new Report(archived, failedGroups);
            }
        }
    }

    // TreeMap：月份依序存，log 和物件儲存裡的順序都跟時間一致，比較好對
    private static Map<YearMonth, List<ScheduledEvent>> byEndMonth(List<ScheduledEvent> events) {
        return events.stream().collect(Collectors.groupingBy(
                e -> YearMonth.from(e.event().end()), TreeMap::new, Collectors.toList()));
    }
}
