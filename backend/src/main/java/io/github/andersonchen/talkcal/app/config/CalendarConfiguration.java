package io.github.andersonchen.talkcal.app.config;

import io.github.andersonchen.talkcal.calendar.adapter.in.assistant.CalendarAssistant;
import io.github.andersonchen.talkcal.calendar.adapter.in.web.ArchiveController;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.ArchiveBucket;
import io.github.andersonchen.talkcal.calendar.adapter.out.archive.s3.S3EventArchiveAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.in.metrics.ActiveUsersMetrics;
import io.github.andersonchen.talkcal.calendar.adapter.out.metrics.micrometer.MicrometerUsageAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai.SpringAiExtractEventsAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcActiveOwnersAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantProposalAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import io.github.andersonchen.talkcal.calendar.application.domain.service.AddEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ArchiveEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.CountActiveUsersService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.FindFreeSlotsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.PlanSessionsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ListArchivedEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ListEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ParseEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.RemoveEventService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.FindEventService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.UpdateEventService;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.domain.service.TrackProposalsService;
import io.github.andersonchen.talkcal.calendar.application.port.in.CountActiveUsersUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.FindFreeSlotsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.PlanSessionsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListArchivedEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ParseEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.FindEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.UpdateEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteArchivedEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.CountActiveOwnersPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.ExtractEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEndedEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.UpdateEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsOncePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;
import io.github.andersonchen.talkcal.app.job.AssistantMemoryArchive;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * 組裝 calendar 模組的 use case 和 adapter
 */
@Configuration(proxyBeanMethods = false)
class CalendarConfiguration {

    // ── AI 助理（入口）─────────────────────────────────────────────────────

    @Bean
    CalendarAssistant calendarAssistant(@Qualifier(ChatModelConfiguration.GUARDED_CHAT_MODEL) ChatModel chatModel,
                                        JdbcAssistantMemoryRepository assistantMemory,
                                        ParseEventsUseCase parseEvents, ListEventsUseCase listEvents,
                                        FindEventUseCase findEvent, FindFreeSlotsUseCase findFreeSlots,
                                        PlanSessionsUseCase planSessions, @Value("${calendar.zone}") ZoneId zone) {
        return new CalendarAssistant(chatModel, assistantMemory::of,
                parseEvents, listEvents, findEvent, findFreeSlots, planSessions, Clock.system(zone));
    }

    @Bean
    JdbcAssistantMemoryRepository assistantMemory(JdbcClient jdbcClient, PlatformTransactionManager transactionManager,
                                                  @Value("${calendar.assistant.memory-turns}") int memoryTurns) {
        return new JdbcAssistantMemoryRepository(jdbcClient, new TransactionTemplate(transactionManager), memoryTurns);
    }

    // ── 助理工具背後的 use case ────────────────────────────────────────────

    // 回傳型別寫實作本身：它實作好幾個 port，寫成其中一個介面的話，其他 port 注入時可能找不到它（要看 bean 建立的順序）
    @Bean
    JdbcCalendarEventAdapter calendarEventStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcCalendarEventAdapter(jdbcClient, new TransactionTemplate(transactionManager));
    }

    // 跟 AI 助理共用 ChatModelConfiguration 包好保護的模型，逾時也一起共用（spring.ai.openai.chat.timeout）；
    // 抽行程失控靠 ExtractionRequest 的 max_tokens 擋
    @Bean
    ExtractEventsPort extractEventsPort(@Qualifier(ChatModelConfiguration.GUARDED_CHAT_MODEL) ChatModel chatModel) {
        return new SpringAiExtractEventsAdapter(chatModel);
    }

    @Bean
    ParseEventsUseCase parseEvents(ExtractEventsPort extractEventsPort, @Value("${calendar.zone}") ZoneId zone) {
        return new ParseEventsService(extractEventsPort, Clock.system(zone));
    }

    @Bean
    ListEventsUseCase listEvents(LoadEventsPort loadEventsPort) {
        return new ListEventsService(loadEventsPort);
    }

    @Bean
    FindEventUseCase findEvent(LoadEventPort loadEventPort) {
        return new FindEventService(loadEventPort);
    }

    @Bean
    FindFreeSlotsUseCase findFreeSlots(LoadEventsPort loadEventsPort, @Value("${calendar.zone}") ZoneId zone) {
        return new FindFreeSlotsService(loadEventsPort, Clock.system(zone));
    }

    @Bean
    PlanSessionsUseCase planSessions(FindFreeSlotsUseCase findFreeSlots) {
        return new PlanSessionsService(findFreeSlots);
    }

    // ── 新增行程、記下卡片的去向 ───────────────────────────────────────────

    // 助理發出的提議卡片（#223）：ProposalStorePort 的實作，歸檔排程也用它刪舊卡片
    @Bean
    JdbcAssistantProposalAdapter assistantProposals(JdbcClient jdbcClient) {
        return new JdbcAssistantProposalAdapter(jdbcClient);
    }

    @Bean
    AddEventsUseCase addEvents(SaveEventsPort saveEventsPort, SaveEventsOncePort saveEventsOncePort,
                               JdbcAssistantProposalAdapter assistantProposals, RecordUsagePort usage) {
        return new AddEventsService(saveEventsPort, saveEventsOncePort, assistantProposals, usage);
    }

    @Bean
    TrackProposalsUseCase trackProposals(JdbcAssistantProposalAdapter assistantProposals, RecordUsagePort usage) {
        return new TrackProposalsService(assistantProposals, usage);
    }

    @Bean
    RecordUsagePort recordUsage(MeterRegistry registry) {
        return new MicrometerUsageAdapter(registry);
    }

    // ── 改、刪行程 ─────────────────────────────────────────────────────────

    @Bean
    UpdateEventUseCase updateEvent(UpdateEventPort updateEventPort, LoadEventPort loadEventPort) {
        return new UpdateEventService(updateEventPort, loadEventPort);
    }

    @Bean
    RemoveEventUseCase removeEvent(DeleteEventPort deleteEventPort) {
        return new RemoveEventService(deleteEventPort);
    }

    // ── 觀測：活躍使用者 ───────────────────────────────────────────────────

    @Bean
    CountActiveOwnersPort countActiveOwners(JdbcClient jdbcClient) {
        return new JdbcActiveOwnersAdapter(jdbcClient);
    }

    // 活躍使用者算的是真實的時間點（created_at 帶時區），用 UTC 就好，跟行事曆的牆上時間無關
    @Bean
    CountActiveUsersUseCase countActiveUsers(CountActiveOwnersPort countActiveOwners) {
        return new CountActiveUsersService(countActiveOwners, Clock.systemUTC());
    }

    // MeterBinder 型別的 bean，actuator 會自動拿去 bindTo 自己的 registry，不必手動註冊。
    // 一分鐘重數一次：活躍人數變得很慢，Alloy 15 秒抓一次只會讀到快取
    @Bean
    ActiveUsersMetrics activeUsersMetrics(CountActiveUsersUseCase countActiveUsers) {
        return new ActiveUsersMetrics(countActiveUsers, Duration.ofMinutes(1), Clock.systemUTC());
    }

    // ── 資料歸檔（#54）─────────────────────────────────────────────────────

    // 歸檔的檔名帶著「存的時間」，用 UTC：跟 S3 自己記的時間同一個基準，不必換算
    @Bean
    EventArchivePort eventArchive(ArchiveBucket archiveBucket) {
        return new S3EventArchiveAdapter(archiveBucket, Clock.systemUTC());
    }

    // 一批 500 筆：一筆行程不到 1KB，一批讀進記憶體也才幾百 KB
    @Bean
    ArchiveEventsUseCase archiveEvents(LoadEndedEventsPort loadEndedEvents, EventArchivePort eventArchive,
                                       DeleteArchivedEventsPort deleteArchivedEvents) {
        return new ArchiveEventsService(loadEndedEvents, eventArchive, deleteArchivedEvents, 500);
    }

    @Bean
    ListArchivedEventsUseCase listArchivedEvents(EventArchivePort eventArchive) {
        return new ListArchivedEventsService(eventArchive);
    }

    // 還原 API 放回的對話（行程不放回，見 listArchivedEvents）：controller 不認識 app.job，這裡把方法接過去（跟 assistantMemory::of 同一個做法）
    @Bean
    ArchiveController.RestoreConversations restoreConversations(AssistantMemoryArchive assistantMemoryArchive) {
        return assistantMemoryArchive::restore;
    }
}
