package io.github.andersonchen.talkcal.calendar.application.domain.service;

import io.github.andersonchen.talkcal.calendar.application.domain.model.ArchivePeriod;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListArchivedEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 實作「查看已經封存的行程」（#274）：讀那幾個月的包，挑出跟範圍重疊的。
 *
 * 只依賴歸檔的 port，沒有任何寫資料庫的 port：查看不會把行程放回行事曆，隔天的排程也就不會再封存一次。
 * 要讀哪幾個月、怎樣算重疊，都交給 ArchivePeriod。
 */
public final class ListArchivedEventsService implements ListArchivedEventsUseCase {

    // 跟月曆一樣依開始、結束、id 排（LoadEventsPort）：同一個時間的幾筆，每次查順序都一樣
    private static final Comparator<ScheduledEvent> CALENDAR_ORDER =
            Comparator.comparing((ScheduledEvent e) -> e.event().start())
                    .thenComparing(e -> e.event().end())
                    .thenComparing(e -> e.id().value());

    private final EventArchivePort archive;

    public ListArchivedEventsService(EventArchivePort archive) {
        this.archive = Objects.requireNonNull(archive, "archive 不可為 null");
    }

    @Override
    public List<ScheduledEvent> listArchivedEvents(OwnerId owner, LocalDate from, LocalDate to) {
        if (owner == null) {
            throw new IllegalArgumentException("擁有者不可為 null");
        }
        // 先建 ArchivePeriod：不合規的範圍（倒過來、太長）連物件儲存都不會碰到
        ArchivePeriod period = new ArchivePeriod(from, to);
        return archive.load(owner, period.firstEndMonth(), period.lastEndMonth()).stream()
                .filter(scheduled -> period.overlaps(scheduled.event()))
                .sorted(CALENDAR_ORDER)
                .toList();
    }
}
