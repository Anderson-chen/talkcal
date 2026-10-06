package eat;

import eat.calendar.adapter.out.extraction.springai.SpringAiExtractEventsAdapter;
import eat.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import eat.calendar.application.domain.service.AddEventsService;
import eat.calendar.application.domain.service.ListEventsService;
import eat.calendar.application.domain.service.ParseEventsService;
import eat.calendar.application.domain.service.RemoveEventService;
import eat.calendar.application.port.in.AddEventsUseCase;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
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
 */
@Configuration(proxyBeanMethods = false)
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
