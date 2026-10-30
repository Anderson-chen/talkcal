package io.github.andersonchen.talkcal.app.job;

import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.Objects;

/**
 * 每天把舊行程搬到歸檔（#54）：結束超過保留期限的，交給 ArchiveEventsUseCase 壓縮、上傳、存好才刪。
 *
 * 這裡只管「什麼時候跑」和「多舊算舊」，怎麼搬（先存再刪、一包失敗只留那一包）是 core 的規則（ArchiveEventsService）。
 * 跟 AssistantMemoryArchive 不同：行程本來就在 core 裡，所以走 use case，不直接碰 adapter。
 *
 * 保留期限用 Period（天、月、年），不是 Duration：行程是牆上時間（LocalDateTime），「兩年前」要在日曆上算。
 * clock 要帶 calendar.zone 的時區：「現在幾點」一定得在某個時區裡問（理由同 CalendarConfiguration 的 parseEvents）。
 */
public final class EventArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(EventArchiveJob.class);

    private final ArchiveEventsUseCase archiveEvents;
    private final Period retention;
    private final Clock clock;

    public EventArchiveJob(ArchiveEventsUseCase archiveEvents, Period retention, Clock clock) {
        this.archiveEvents = Objects.requireNonNull(archiveEvents, "archiveEvents 不可為 null");
        this.retention = Objects.requireNonNull(retention, "retention 不可為 null");
        this.clock = Objects.requireNonNull(clock, "clock 不可為 null");
        if (retention.isNegative() || retention.isZero()) {
            // 0 會把今天剛結束的行程也搬走，月曆上「今天」那格就空了
            throw new IllegalArgumentException("保留期限要大於 0：" + retention);
        }
    }

    // 四點半：跟對話的歸檔（四點）錯開，兩個都要讀寫資料庫和物件儲存，不必擠在同一刻
    @Scheduled(cron = "0 30 4 * * *", zone = "${calendar.zone}")
    void run() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(retention);
        ArchiveEventsUseCase.Report report = archiveEvents.archiveEndedBefore(cutoff);
        if (report.failedGroups() > 0) {
            log.warn("舊行程有 {} 包沒存進歸檔，留在資料庫明天再試；已歸檔 {} 筆（{} 之前結束的）",
                    report.failedGroups(), report.archived(), cutoff);
        } else {
            log.info("舊行程歸檔了 {} 筆（{} 之前結束的）", report.archived(), cutoff);
        }
    }
}
