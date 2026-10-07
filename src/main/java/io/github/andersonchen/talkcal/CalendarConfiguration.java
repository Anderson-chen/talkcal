package io.github.andersonchen.talkcal;

import io.github.andersonchen.talkcal.calendar.adapter.in.assistant.CalendarAssistant;
import io.github.andersonchen.talkcal.calendar.adapter.out.extraction.springai.SpringAiExtractEventsAdapter;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcAssistantMemoryRepository;
import io.github.andersonchen.talkcal.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import io.github.andersonchen.talkcal.calendar.application.domain.service.AddEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.FindFreeSlotsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ListEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ParseEventsService;
import io.github.andersonchen.talkcal.calendar.application.domain.service.RemoveEventService;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.FindFreeSlotsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.ParseEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.DeleteEventPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.ExtractEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.LoadEventsPort;
import io.github.andersonchen.talkcal.calendar.application.port.out.SaveEventsPort;

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
 * calendar 模組的接線：哪個 port 用哪個實作、use case 怎麼 new 出來。一個模組一個 Configuration。
 *
 * 放在根 package、類別不 public、proxyBeanMethods = false：
 * 組裝根站在所有模組外面，才有資格認識 adapter 的具體類別（ArchitectureTest 只准這一圈認識它們）；
 * 沒有程式該直接引用它，Bean 方法之間也不互相呼叫。
 *
 * 跟模型有關的 bean（ChatModel）是 Spring AI 依 application.properties 的 spring.ai.* 自動建的 ——
 * 連到哪、等多久、生成上限是設定，不是程式。
 *
 * 這裡要下的決定：
 * - ExtractEventsPort 用 Spring AI 的 ChatModel（跟 AI 助理共用同一個，自動組裝的那一個）
 * - 行程存 PostgreSQL（一個實作同時當 Save / Load / DeleteEventPort）
 * - 「現在」用哪個時區的時鐘
 * - AI 助理的舊對話留多久（AssistantMemoryCleanup 每天清一次，所以要開排程）
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class CalendarConfiguration {

    // ChatModel 是 Spring AI 依 spring.ai.openai.chat.* 自動建好的，跟 AI 助理背後是同一個。
    // 抽行程自己的需求（溫度 0、max_tokens 1024、JSON Schema）在每個請求的選項裡帶，不必另建一個。
    // 代價：逾時也跟助理共用（2 分鐘）。以前這裡自己建 RestClient、30 秒就放棄；現在靠 max_tokens 擋住失控
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
     * 直接拿 ChatModel（跟抽行程背後是同一個），agent loop 由 CalendarAssistant 自己跑。
     * 記憶不用 Spring AI 自動組裝的那個（JdbcChatMemoryRepository 會把工具訊息濾掉），用自己的表，原因寫在 V7。
     */
    @Bean
    CalendarAssistant calendarAssistant(ChatModel chatModel, JdbcAssistantMemoryRepository assistantMemory,
                                        ParseEventsUseCase parseEvents, ListEventsUseCase listEvents,
                                        FindFreeSlotsUseCase findFreeSlots, @Value("${calendar.zone}") ZoneId zone) {
        return new CalendarAssistant(chatModel, assistantMemory, parseEvents, listEvents, findFreeSlots, Clock.system(zone));
    }

    @Bean
    AssistantMemoryCleanup assistantMemoryCleanup(JdbcAssistantMemoryRepository assistantMemory,
                                                  @Value("${calendar.assistant.retention}") Duration retention) {
        return new AssistantMemoryCleanup(assistantMemory, retention, Clock.systemUTC());
    }

    // 助理讀寫、清理工作刪除，用的是同一張表、同一個實作
    @Bean
    JdbcAssistantMemoryRepository assistantMemory(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
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
