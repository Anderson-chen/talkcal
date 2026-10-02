package eat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * 觀測的全域規則：什麼要觀測、什麼不要。
 *
 * 觀測是橫切關注點 —— 每個請求、每次往外呼叫都要量，但量的方式跟「業務在做什麼」無關。
 * 所以規則全部集中在這一個檔案，業務程式碼（controller、service、adapter）和接線（ConversationConfiguration）
 * 都不必為了觀測而改：
 *
 *   進來的請求  —— Spring Boot 自動加的 ServerHttpObservationFilter 量（這裡只決定哪些不量）
 *   往外的呼叫  —— 這裡的 aspect 在每個 outbound port 外面包一層量
 *
 * 跟 ConversationConfiguration 分開：那邊回答「conversation 模組誰接誰」，
 * 這邊回答的是整個 app 共用的維運決定，不屬於任何一個功能模組。
 */
@Configuration(proxyBeanMethods = false)
class ObservationConfiguration {

    // /actuator/* 的請求不觀測：不開 trace、也不記 http.server.requests 指標。
    //
    // 為什麼：ops/ 的 Alloy 每 15 秒來抓一次 /actuator/prometheus、Docker 每幾秒打一次 /actuator/health，
    // 一小時就是幾百筆 trace，真正的提問在 Tempo 上被淹掉（跟 AccessLogFilter 不記 actuator 同一個理由）。
    // 這些請求的健康本身已經有人看著：抓不到時 Mimir 裡的 up 會變 0，健康檢查失敗時 Docker 會標 unhealthy。
    //
    // 為什麼用 ObservationPredicate 而不是只關 trace：trace 和指標都從同一個 Observation 長出來，
    // 在源頭擋掉最單純。代價是 /actuator 的 http.server.requests 也沒了 —— 儀表板本來就把它濾掉，不受影響。
    @Bean
    ObservationPredicate skipActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith("/actuator"));
    }

    @Bean
    OutboundPortObservation outboundPortObservation(ObservationRegistry observationRegistry) {
        return new OutboundPortObservation(observationRegistry);
    }

    /**
     * 每一次 outbound port 的呼叫（core 往外要東西的每一個點）都包成一個 Observation：
     * 在 trace 上是一個子 span（名稱就是 port 的方法名，例如 retrievePassages、generateReply），
     * 在指標上是一組 eat.port.out 計時（Prometheus 格式裡是 eat_port_out_seconds_*）。
     *
     * 為什麼切在 port 而不是切在 HTTP：
     * - port 是 Clean Architecture 的邊界，「core 往外呼叫花了多久」正好是一次 port 呼叫。
     *   之後換成 Claude / OpenAI 的 adapter、或改用別的 HTTP 函式庫，都自動被量到，不必再包一次。
     * - adapter 用的是 JDK 的 HttpClient，Spring 管不到它，要在 HTTP 層量就得改 adapter 的程式碼。
     * 代價：看不到 HTTP 層的細節（狀態碼、連線花多久），也不會在往外的請求帶 traceparent 標頭
     * （llama.cpp 不讀它，目前沒影響）。
     *
     * 切點用字串寫 package，不 import 任何 port：這個類別不必認識 conversation 模組裡的型別，
     * 之後新增模組，只要 outbound port 也放在 ..application.port.out 底下就自動被量。
     * 「..*+」= 那個 package 底下任何型別的「實作」—— Spring 包的是實作的 bean，不是介面本身。
     */
    @Aspect
    static final class OutboundPortObservation {

        private final ObservationRegistry observationRegistry;

        OutboundPortObservation(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
        }

        @Around("execution(* eat..application.port.out..*+.*(..))")
        Object observe(ProceedingJoinPoint call) throws Throwable {
            String port = call.getSignature().getDeclaringType().getSimpleName();
            String method = call.getSignature().getName();
            return Observation.createNotStarted("eat.port.out", observationRegistry)
                    // span 的名稱：Tempo 上一眼看出是檢索還是生成
                    .contextualName(method)
                    // 低基數標籤：值只有幾種（幾個 port、幾個方法、幾個實作），會變成指標的 label 和 span 的屬性。
                    // adapter 記實作的類別名：同一個 port 換了供應商時，分得出是哪一家慢
                    .lowCardinalityKeyValue("port", port)
                    .lowCardinalityKeyValue("method", method)
                    .lowCardinalityKeyValue("adapter", call.getTarget().getClass().getSimpleName())
                    // 丟出的例外會被記成這個 span 的錯誤（error 標籤 + span 狀態 ERROR），再原樣往上丟
                    // 寫成 lambda 而不是 call::proceed：方法參考同時配得上「有回傳值」和「沒回傳值」兩個多載，編譯器分不出來
                    .observeChecked(() -> call.proceed());
        }
    }
}
