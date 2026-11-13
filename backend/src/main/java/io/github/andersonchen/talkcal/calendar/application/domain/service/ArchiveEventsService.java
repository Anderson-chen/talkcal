package io.github.andersonchen.talkcal.calendar.application.domain.service;

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
 * 先存進歸檔、存成功了才刪資料庫：先刪再存的話，存的那一步一失敗資料就沒了。
 * 先存再刪最壞是存好了、刪之前當掉：兩邊各一份，下次排程再存一包、再刪一次，歸檔裡多一份重複的，
 * load 時依 id 去重。重複比遺失便宜。
 *
 * 一個人、一個月一包（依行程結束的月份）：
 * - 依人分：查看、刪帳號都是某個人的，讀和刪都不必打開來挑
 * - 依月分：查看是某段時間，只讀那幾個月的包
 * - 每包各自存好才刪，一包失敗只有那一包留在資料庫，下次再試
 *
 * 一次讀 batchSize 筆，搬完再讀下一批：資料再多，記憶體也只放一批。
 * app.job.AssistantMemoryArchive 是同一個迴圈、同一組停止條件，改一邊要對照另一邊；
 * 不共用 helper：對話不在 core 裡，共用的話 core 要認識 app.job。
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
                        // 這一包一筆都不刪，留在資料庫等下次
                        failedGroups++;
                        continue;
                    }
                    deletedThisBatch += deleteArchived.deleteArchived(owner.getKey(), month.getValue());
                }
            }
            archived += deletedThisBatch;
            // 不滿一批 = 舊的都讀完了；有包失敗、或一筆都沒刪到（全被改成未來的）= 再讀也是同一批，停下來免得繞不完
            if (loaded < batchSize || failedGroups > 0 || deletedThisBatch == 0) {
                return new Report(archived, failedGroups);
            }
        }
    }

    // TreeMap：依月份順序存，log 和物件儲存裡的順序跟時間一致，比較好對
    private static Map<YearMonth, List<ScheduledEvent>> byEndMonth(List<ScheduledEvent> events) {
        return events.stream().collect(Collectors.groupingBy(
                e -> YearMonth.from(e.event().end()), TreeMap::new, Collectors.toList()));
    }
}
