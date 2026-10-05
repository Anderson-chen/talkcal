package eat;

import eat.calendar.adapter.out.extraction.llamacpp.LlamaCppExtractEventsAdapter;
import eat.calendar.adapter.out.persistence.postgres.JdbcCalendarEventAdapter;
import eat.calendar.application.domain.service.AddEventsService;
import eat.calendar.application.domain.service.ListEventsService;
import eat.calendar.application.domain.service.ParseEventsService;
import eat.calendar.application.port.in.AddEventsUseCase;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.ParseEventsUseCase;
import eat.calendar.application.port.out.ExtractEventsPort;
import eat.calendar.application.port.out.LoadEventsPort;
import eat.calendar.application.port.out.SaveEventsPort;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.URI;
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
 * - ExtractEventsPort 用 llama.cpp（跟聊天同一台 server，但讀取逾時不同）
 * - 行程存 PostgreSQL（一個實作同時當 Save / LoadEventsPort）
 * - 「現在」用哪個時區的時鐘
 */
@Configuration(proxyBeanMethods = false)
class CalendarConfiguration {

    // 跟聊天同一台 llama-server（llamacpp.baseUri），但另建一個 RestClient：
    // 抽行程實測 1～4 秒、又有 max_tokens 上限，30 秒還沒回就是壞了，該快點回 502，
    // 不該讓使用者盯著預覽轉兩分鐘（聊天那邊的 readTimeout）。同一個位址、不同逾時 = 兩個實例。
    @Bean
    ExtractEventsPort extractEventsPort(RestClient.Builder restClientBuilder,
                                        ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
                                        @Value("${llamacpp.baseUri}") URI baseUri,
                                        @Value("${llamacpp.connectTimeout}") Duration connectTimeout,
                                        @Value("${llamacpp.extractionReadTimeout}") Duration readTimeout) {
        // 跟 ConversationConfiguration.llamaCppClient 同一個做法。重複這幾行而不是去呼叫那邊：
        // 兩個 Configuration 互不引用，等 llama.cpp 的 HTTP 那段抽成共用元件時一起收
        RestClient llamaCpp = restClientBuilder
                .baseUrl(baseUri.toString())
                .requestFactory(requestFactoryBuilder.build(
                        HttpClientSettings.defaults().withTimeouts(connectTimeout, readTimeout)))
                .build();
        return new LlamaCppExtractEventsAdapter(llamaCpp);
    }

    // 跟 conversationStore 同一個理由：一個實作兩個 port，回傳型別只能寫實作本身
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
}
