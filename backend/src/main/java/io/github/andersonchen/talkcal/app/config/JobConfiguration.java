package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.app.job.AssistantMemoryArchive;
import io.github.andersonchen.talkcal.app.job.EventArchiveJob;
import io.github.andersonchen.talkcal.app.job.GuestAccountCleanup;
import io.github.andersonchen.talkcal.auth.application.port.in.ExpireGuestsUseCase;
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

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class JobConfiguration {

    // 對話歸檔的 key 依最後說話的月份分，月份要照使用者的時區算
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
    GuestAccountCleanup guestAccountCleanup(ExpireGuestsUseCase expireGuests,
                                            @Value("${talkcal.guest.lifetime}") Duration lifetime) {
        return new GuestAccountCleanup(expireGuests, lifetime, Clock.systemUTC());
    }

    @Bean
    EventArchiveJob eventArchiveJob(ArchiveEventsUseCase archiveEvents,
                                    @Value("${calendar.event.retention}") Period retention,
                                    @Value("${calendar.zone}") ZoneId zone) {
        return new EventArchiveJob(archiveEvents, retention, Clock.system(zone));
    }
}
