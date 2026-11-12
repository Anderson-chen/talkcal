package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.app.job.AssistantMemoryArchive;
import io.github.andersonchen.talkcal.app.job.EventArchiveJob;
import io.github.andersonchen.talkcal.app.job.IdempotencyKeyCleanup;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantProposalAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;

import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排程工作（app.job）：每天清掉過期的冪等鍵、把舊的行程和對話搬去歸檔。
 * 排程在這裡開，因為 @Scheduled 只出現在這些類別上。工作要用的 adapter、use case、bucket 都從別的設定檔注入，這裡只建工作本身。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class JobConfiguration {

    // 時鐘帶 calendar.zone：對話歸檔的 key 依「最後說話的月份」分，月份要用使用者看到的那個時區算
    @Bean
    AssistantMemoryArchive assistantMemoryArchive(JdbcAssistantMemoryRepository assistantMemory,
                                                  JdbcAssistantProposalAdapter assistantProposals, ArchiveBucket archiveBucket,
                                                  @Value("${calendar.assistant.retention}") Duration retention,
                                                  @Value("${calendar.zone}") ZoneId zone) {
        return new AssistantMemoryArchive(assistantMemory, assistantProposals, archiveBucket, retention, Clock.system(zone));
    }

    @Bean
    IdempotencyKeyCleanup idempotencyKeyCleanup(JdbcCalendarEventAdapter calendarEventStore,
                                                @Value("${calendar.idempotency.retention}") Duration retention) {
        return new IdempotencyKeyCleanup(calendarEventStore, retention, Clock.systemUTC());
    }

    @Bean
    EventArchiveJob eventArchiveJob(ArchiveEventsUseCase archiveEvents,
                                    @Value("${calendar.event.retention}") Period retention,
                                    @Value("${calendar.zone}") ZoneId zone) {
        return new EventArchiveJob(archiveEvents, retention, Clock.system(zone));
    }
}
