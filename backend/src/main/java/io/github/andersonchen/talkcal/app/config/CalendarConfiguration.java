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

    // 對話最多留幾輪（一輪 = 使用者一句話，加上之後的工具呼叫和回覆）
    private static final int ASSISTANT_MEMORY_TURNS = 10;

    // 跟 AI 助理用同一個模型（ChatModelConfiguration 包好保護的那個）。
    // 抽行程自己的需求（溫度 0、max_tokens 1024、JSON Schema）在每個請求的選項裡帶，不必另建一個。
    // 代價：逾時也跟助理共用（2 分鐘）。以前這裡自己建 RestClient、30 秒就放棄；現在靠 max_tokens 擋住失控
    @Bean
    ExtractEventsPort extractEventsPort(@Qualifier(ChatModelConfiguration.GUARDED_CHAT_MODEL) ChatModel chatModel) {
        return new SpringAiExtractEventsAdapter(chatModel);
    }

    // 一個實作好幾個 port：回傳型別只能寫實作本身，下面的 use case 照樣用各自的 port 介面收它
    @Bean
    JdbcCalendarEventAdapter calendarEventStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcCalendarEventAdapter(jdbcClient, new TransactionTemplate(transactionManager));
    }

    /**
     * 時區寫明，不用 Clock.systemDefaultZone()：
     * 雖然決定了「不處理時區」（行程存牆上時間），但「現在幾點」一定得在某個時區裡問。
     * JVM 的預設時區跟著機器走 —— 本機是台北，容器裡是 UTC。不寫明的話，
     * 一部署到 Docker，早上九點就變成凌晨一點，「今天晚上」會被算到錯的那天。
     */
    @Bean
    ParseEventsUseCase parseEvents(ExtractEventsPort extractEventsPort, @Value("${calendar.zone}") ZoneId zone) {
        return new ParseEventsService(extractEventsPort, Clock.system(zone));
    }

    // 找空檔也要知道「現在」（過去的時間不算），時區跟解析用同一個
    @Bean
    FindFreeSlotsUseCase findFreeSlots(LoadEventsPort loadEventsPort, @Value("${calendar.zone}") ZoneId zone) {
        return new FindFreeSlotsService(loadEventsPort, Clock.system(zone));
    }

    @Bean
    PlanSessionsUseCase planSessions(FindFreeSlotsUseCase findFreeSlots) {
        return new PlanSessionsService(findFreeSlots);
    }

    /**
     * AI 助理：模型 + 自己的對話記憶 + 六個工具（工具背後是上面那些 use case）。
     *
     * 直接拿 ChatModel（跟抽行程同一個），agent loop 由 CalendarAssistant 自己跑。
     * 記憶不用 Spring AI 自動組裝的那個（JdbcChatMemoryRepository 會把工具訊息濾掉），用自己的表，原因寫在 V7。
     * 記憶依擁有者分開（V11）：助理每一句話先拿「這個人名下的記憶」（assistantMemory::of），再照框架的介面讀寫。
     */
    @Bean
    CalendarAssistant calendarAssistant(@Qualifier(ChatModelConfiguration.GUARDED_CHAT_MODEL) ChatModel chatModel,
                                        JdbcAssistantMemoryRepository assistantMemory,
                                        ParseEventsUseCase parseEvents, ListEventsUseCase listEvents,
                                        FindEventUseCase findEvent, FindFreeSlotsUseCase findFreeSlots,
                                        PlanSessionsUseCase planSessions, @Value("${calendar.zone}") ZoneId zone) {
        return new CalendarAssistant(chatModel, assistantMemory::of,
                parseEvents, listEvents, findEvent, findFreeSlots, planSessions, Clock.system(zone));
    }

    // 助理發出的提議卡片（#223）：ProposalStorePort 的實作，歸檔排程也用它刪舊卡片
    @Bean
    JdbcAssistantProposalAdapter assistantProposals(JdbcClient jdbcClient) {
        return new JdbcAssistantProposalAdapter(jdbcClient);
    }

    // 助理讀寫、歸檔工作搬走，用的是同一張表、同一個實作。存的時候只留最近幾輪，助理讀到的就是這幾輪
    @Bean
    JdbcAssistantMemoryRepository assistantMemory(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcAssistantMemoryRepository(jdbcClient, new TransactionTemplate(transactionManager), ASSISTANT_MEMORY_TURNS);
    }

    @Bean
    AddEventsUseCase addEvents(SaveEventsPort saveEventsPort, SaveEventsOncePort saveEventsOncePort,
                               JdbcAssistantProposalAdapter assistantProposals, RecordUsagePort usage) {
        return new AddEventsService(saveEventsPort, saveEventsOncePort, assistantProposals, usage);
    }

    // MeterRegistry 是 actuator 自動建好的那一個，/actuator/prometheus 吐的就是它裡面的東西
    @Bean
    RecordUsagePort recordUsage(MeterRegistry registry) {
        return new MicrometerUsageAdapter(registry);
    }

    @Bean
    TrackProposalsUseCase trackProposals(JdbcAssistantProposalAdapter assistantProposals, RecordUsagePort usage) {
        return new TrackProposalsService(assistantProposals, usage);
    }

    // 活躍使用者算的是真實的時間點（created_at 帶時區），用 UTC 就好，跟行事曆的牆上時間無關
    @Bean
    CountActiveOwnersPort countActiveOwners(JdbcClient jdbcClient) {
        return new JdbcActiveOwnersAdapter(jdbcClient);
    }

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

    @Bean
    ListEventsUseCase listEvents(LoadEventsPort loadEventsPort) {
        return new ListEventsService(loadEventsPort);
    }

    @Bean
    RemoveEventUseCase removeEvent(DeleteEventPort deleteEventPort) {
        return new RemoveEventService(deleteEventPort);
    }

    @Bean
    FindEventUseCase findEvent(LoadEventPort loadEventPort) {
        return new FindEventService(loadEventPort);
    }

    @Bean
    UpdateEventUseCase updateEvent(UpdateEventPort updateEventPort, LoadEventPort loadEventPort) {
        return new UpdateEventService(updateEventPort, loadEventPort);
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

    // 查看封存的行程（#274）：只讀歸檔，不接任何寫資料庫的 port
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
