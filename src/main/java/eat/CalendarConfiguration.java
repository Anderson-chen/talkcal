package eat;

import eat.calendar.adapter.in.assistant.CalendarAssistant;
import eat.calendar.adapter.out.extraction.springai.SpringAiExtractEventsAdapter;
import eat.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository;
import eat.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import eat.calendar.application.domain.service.AddEventsService;
import eat.calendar.application.domain.service.FindFreeSlotsService;
import eat.calendar.application.domain.service.ListEventsService;
import eat.calendar.application.domain.service.ParseEventsService;
import eat.calendar.application.domain.service.RemoveEventService;
import eat.calendar.application.port.in.AddEventsUseCase;
import eat.calendar.application.port.in.FindFreeSlotsUseCase;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.ParseEventsUseCase;
import eat.calendar.application.port.in.RemoveEventUseCase;
import eat.calendar.application.port.out.DeleteEventPort;
import eat.calendar.application.port.out.ExtractEventsPort;
import eat.calendar.application.port.out.LoadEventsPort;
import eat.calendar.application.port.out.SaveEventsPort;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * calendar 模組的接線：跟 ConversationConfiguration 並列，一個模組一個。
 *
 * 理由跟那邊一樣（站在所有模組外面才有資格認識它們、類別不 public、proxyBeanMethods = false），這裡不重抄。
 * 兩個 Configuration 都在 package eat，但彼此不引用：
 * ArchitectureTest 的 modulesMustNotDependOnEachOther 只管 eat 底下的模組，
 * 組裝根同時認識兩個模組是它的工作，兩個 Configuration 互相認識就沒必要了。
 *
 * 這裡要下的決定：
 * - ExtractEventsPort 用 Spring AI 的 ChatModel（跟聊天共用同一個，自動組裝的那一個）
 * - 行程存 PostgreSQL（一個實作同時當 Save / Load / DeleteEventPort）
 * - 「現在」用哪個時區的時鐘
 * - AI 助理的舊對話留多久（AssistantMemoryCleanup 每天清一次，所以要開排程）
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CalendarConfiguration {

    // ChatModel 是 Spring AI 依 spring.ai.openai.chat.* 自動建好的，跟聊天的 ChatClient 背後是同一個。
    // 抽行程自己的需求（溫度 0、max_tokens 1024、JSON Schema）在每個請求的選項裡帶，不必另建一個。
    // 代價：逾時也跟聊天共用（2 分鐘）。以前這裡自己建 RestClient、30 秒就放棄；現在靠 max_tokens 擋住失控
    @Bean
    ExtractEventsPort extractEventsPort(ChatModel chatModel) {
        return new SpringAiExtractEventsAdapter(chatModel);
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
     * 直接拿 ChatModel（跟抽行程、聊天背後是同一個），agent loop 由 CalendarAssistant 自己跑。
     * 記憶不用 Spring AI 自動組裝的那個（JdbcChatMemoryRepository 會把工具訊息濾掉），用自己的表，原因寫在 V7。
     */
    @Bean
    CalendarAssistant calendarAssistant(ChatModel chatModel, JdbcClient jdbcClient, PlatformTransactionManager transactionManager,
                                        ParseEventsUseCase parseEvents, ListEventsUseCase listEvents,
                                        FindFreeSlotsUseCase findFreeSlots, @Value("${calendar.zone}") ZoneId zone) {
        return new CalendarAssistant(chatModel, assistantMemory(jdbcClient, transactionManager),
                parseEvents, listEvents, findFreeSlots, Clock.system(zone));
    }

    @Bean
    AssistantMemoryCleanup assistantMemoryCleanup(JdbcClient jdbcClient, PlatformTransactionManager transactionManager,
                                                  @Value("${calendar.assistant.retention}") Duration retention) {
        return new AssistantMemoryCleanup(assistantMemory(jdbcClient, transactionManager), retention, Clock.systemUTC());
    }

    // 助理和清理工作各 new 一個（它沒有狀態，兩個跟一個一樣）。刻意不註冊成 bean：
    // 它的型別是 ChatMemoryRepository，一進容器，聊天那邊自動組裝的 ChatMemory 就會撞見兩個、不知道用哪個
    private static JdbcAssistantMemoryRepository assistantMemory(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcAssistantMemoryRepository(jdbcClient, new TransactionTemplate(transactionManager));
    }

    @Bean
    AddEventsUseCase addEvents(SaveEventsPort saveEventsPort) {
        return new AddEventsService(saveEventsPort);
    }

    @Bean
    ListEventsUseCase listEvents(LoadEventsPort loadEventsPort) {
        return new ListEventsService(loadEventsPort);
    }

    @Bean
    RemoveEventUseCase removeEvent(DeleteEventPort deleteEventPort) {
        return new RemoveEventService(deleteEventPort);
    }
}
