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
import io.github.andersonchen.talkcal.calendar.application.domain.service.ListEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ParseEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.RemoveEventService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.RestoreEventsService;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ArchiveEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.domain.service.TrackProposalsService;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.CountActiveUsersUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.FindFreeSlotsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ParseEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.RestoreEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteArchivedEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.in.TrackProposalsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.CountActiveOwnersPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.ExtractEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEndedEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RestoreEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.RecordUsagePort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;
import io.github.andersonchen.talkcal.app.job.AssistantMemoryArchive;
import io.github.andersonchen.talkcal.app.job.EventArchiveJob;

import io.github.andersonchen.talkcal.calendar.adapter.shared.GuardedChatModel;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;

/**
 * calendar 模組的接線：哪個 port 用哪個實作、use case 怎麼 new 出來。一個模組一個 Configuration。
 *
 * 放在 app.config、類別不 public、proxyBeanMethods = false：
 * 所有「誰接誰」的決定集中在 app.config 這一個 package —— 想知道某個介面背後是哪個實作，只要看這裡。
 * 組裝根站在所有模組外面，才有資格認識 adapter 的具體類別（ArchitectureTest 只准 app.config、app.job 認識它們）；
 * 沒有程式該直接引用它，Bean 方法之間也不互相呼叫。
 *
 * 跟模型有關的 bean（ChatModel）是 Spring AI 依 application.properties 的 spring.ai.* 自動建的 ——
 * 連到哪、等多久、生成上限是設定，不是程式。
 *
 * 這裡要下的決定：
 * - ExtractEventsPort 用 Spring AI 的 ChatModel（跟 AI 助理共用同一個，自動組裝的那一個）
 * - 行程存 PostgreSQL（一個實作同時當 Save / Load / DeleteEventPort，和歸檔要的那三個）
 * - 「現在」用哪個時區的時鐘
 * - 舊資料歸檔到哪（S3 相容的物件儲存）、AI 助理的對話和行程各留多久（#54；排程每天搬一次，所以要開排程）
 * - 產品用量記到哪（#36）：Micrometer 的 counter；活躍使用者從資料庫數，掛成 gauge
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CalendarConfiguration {

    private static final String GUARDED_CHAT_MODEL = "guardedChatModel";

    // ChatModel 是 Spring AI 依 spring.ai.openai.chat.* 自動建好的，跟 AI 助理背後是同一個。
    // 抽行程自己的需求（溫度 0、max_tokens 1024、JSON Schema）在每個請求的選項裡帶，不必另建一個。
    // 代價：逾時也跟助理共用（2 分鐘）。以前這裡自己建 RestClient、30 秒就放棄；現在靠 max_tokens 擋住失控
    @Bean
    ExtractEventsPort extractEventsPort(@Qualifier(GUARDED_CHAT_MODEL) ChatModel chatModel) {
        return new SpringAiExtractEventsAdapter(chatModel);
    }

    /**
     * 模型外面包一層 GuardedChatModel（#44），助理和抽行程都用這一個：背後是同一顆模型，壞就一起壞。
     * 規則在 application.properties 的 resilience4j.*.instances.chat-model。
     *
     * 每一次呼叫的逾時也是在這裡補上的（GuardedChatModel.getOptions）：Spring AI 自動建的那個沒包保護，每次呼叫只等 60 秒，
     * 之後有新的地方要叫模型，一樣用 @Qualifier(GUARDED_CHAT_MODEL) 拿這一個。
     *
     * defaultCandidate = false：它也是 ChatModel，一般按型別要 ChatModel 的地方（例如 Spring AI 自己的 ChatClient.Builder）
     * 看不到它、照樣拿 Spring AI 自動建的那個；只有指名 @Qualifier(GUARDED_CHAT_MODEL) 的才拿得到。
     */
    @Bean(name = GUARDED_CHAT_MODEL, defaultCandidate = false)
    ChatModel guardedChatModel(ChatModel chatModel, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
                               @Value("${talkcal.model.busy-retry-after}") Duration busyRetryAfter,
                               @Value("${spring.ai.openai.chat.timeout}") Duration callTimeout) {
        return new GuardedChatModel(chatModel, breakers.circuitBreaker(ResilienceConfiguration.CHAT_MODEL),
                bulkheads.bulkhead(ResilienceConfiguration.CHAT_MODEL), busyRetryAfter, callTimeout);
    }

    // 一個實作三個 port：回傳型別只能寫實作本身，下面的 use case 照樣用各自的 port 介面收它
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

    /**
     * AI 助理：模型 + 自己的對話記憶 + 三個工具（工具背後是上面那些 use case）。
     *
     * 直接拿 ChatModel（跟抽行程背後是同一個），agent loop 由 CalendarAssistant 自己跑。
     * 記憶不用 Spring AI 自動組裝的那個（JdbcChatMemoryRepository 會把工具訊息濾掉），用自己的表，原因寫在 V7。
     * 記憶依擁有者分開（V11）：助理每一句話先拿「這個人名下的記憶」（assistantMemory::of），再照框架的介面讀寫。
     * 模型跟抽行程用同一個包好保護的（見 guardedChatModel）。
     */
    @Bean
    CalendarAssistant calendarAssistant(@Qualifier(GUARDED_CHAT_MODEL) ChatModel chatModel,
                                        JdbcAssistantMemoryRepository assistantMemory,
                                        ParseEventsUseCase parseEvents, ListEventsUseCase listEvents,
                                        FindFreeSlotsUseCase findFreeSlots, @Value("${calendar.zone}") ZoneId zone) {
        return new CalendarAssistant(chatModel, assistantMemory::of,
                parseEvents, listEvents, findFreeSlots, Clock.system(zone));
    }

    // 時鐘帶 calendar.zone：對話歸檔的 key 依「最後說話的月份」分，月份要用使用者看到的那個時區算
    @Bean
    AssistantMemoryArchive assistantMemoryArchive(JdbcAssistantMemoryRepository assistantMemory,
                                                  JdbcAssistantProposalAdapter assistantProposals, ArchiveBucket archiveBucket,
                                                  @Value("${calendar.assistant.retention}") Duration retention,
                                                  @Value("${calendar.zone}") ZoneId zone) {
        return new AssistantMemoryArchive(assistantMemory, assistantProposals, archiveBucket, retention, Clock.system(zone));
    }

    // 助理發出的提議卡片（#223）：ProposalStorePort 的實作，歸檔排程也用它刪舊卡片
    @Bean
    JdbcAssistantProposalAdapter assistantProposals(JdbcClient jdbcClient) {
        return new JdbcAssistantProposalAdapter(jdbcClient);
    }

    // 助理讀寫、歸檔工作搬走，用的是同一張表、同一個實作
    @Bean
    JdbcAssistantMemoryRepository assistantMemory(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcAssistantMemoryRepository(jdbcClient, new TransactionTemplate(transactionManager));
    }

    @Bean
    AddEventsUseCase addEvents(SaveEventsPort saveEventsPort, JdbcAssistantProposalAdapter assistantProposals, RecordUsagePort usage) {
        return new AddEventsService(saveEventsPort, assistantProposals, usage);
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

    // ── 資料歸檔（#54）─────────────────────────────────────────────────────

    /**
     * 歸檔用的 bucket：行程和 AI 助理的對話共用一個。連到哪是設定（talkcal.archive.*）：
     * 本機是 compose 的 RustFS，雲端是 S3（endpoint 空白）。建這個物件不會連線，連不上要到真的上傳時才知道 ——
     * 物件儲存掛了不該讓整個 app 起不來，歸檔晚一天沒關係（沒存好的不會被刪）。
     */
    @Bean
    ArchiveBucket archiveBucket(@Value("${talkcal.archive.endpoint}") String endpoint,
                                @Value("${talkcal.archive.region}") String region,
                                @Value("${talkcal.archive.bucket}") String bucket,
                                @Value("${talkcal.archive.access-key}") String accessKey,
                                @Value("${talkcal.archive.secret-key}") String secretKey) {
        return ArchiveBucket.connect(endpoint, region, bucket, accessKey, secretKey);
    }

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
    RestoreEventsUseCase restoreEvents(EventArchivePort eventArchive, RestoreEventsPort restoreEventsPort) {
        return new RestoreEventsService(eventArchive, restoreEventsPort);
    }

    @Bean
    EventArchiveJob eventArchiveJob(ArchiveEventsUseCase archiveEvents,
                                    @Value("${calendar.event.retention}") Period retention,
                                    @Value("${calendar.zone}") ZoneId zone) {
        return new EventArchiveJob(archiveEvents, retention, Clock.system(zone));
    }

    // 還原 API 要的對話那一半：controller 不認識 app.job，這裡把方法接過去（跟 assistantMemory::of 同一個做法）
    @Bean
    ArchiveController.RestoreConversations restoreConversations(AssistantMemoryArchive assistantMemoryArchive) {
        return assistantMemoryArchive::restore;
    }
}
