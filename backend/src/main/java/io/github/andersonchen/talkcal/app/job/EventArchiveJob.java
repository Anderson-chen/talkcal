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
 * 每天把結束超過保留期限的行程交給 ArchiveEventsUseCase 歸檔。
 * 行程在 core 裡，所以走 use case；對話不在 core 裡，AssistantMemoryArchive 才直接用 adapter。
 *
 * 保留期限用 Period，不用 Duration：行程是牆上時間，「兩年前」要在日曆上算。
 * clock 要帶 calendar.zone 的時區，理由見 application.properties 的 calendar.zone。
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

    // 跟對話的歸檔（四點）錯開：兩個都要讀寫資料庫和物件儲存
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
