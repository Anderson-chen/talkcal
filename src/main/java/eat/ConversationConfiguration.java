package eat;

import eat.conversation.adapter.out.knowledge.MarkdownKnowledgeBase;
import eat.conversation.adapter.out.knowledge.llamacpp.LlamaCppRetrievePassagesAdapter;
import eat.conversation.adapter.out.persistence.postgres.JdbcConversationAdapter;
import eat.conversation.adapter.out.reply.llamacpp.LlamaCppGenerateReplyAdapter;
import eat.conversation.application.domain.service.AskQuestionService;
import eat.conversation.application.port.in.AskQuestionUseCase;
import eat.conversation.application.port.out.GenerateReplyPort;
import eat.conversation.application.port.out.LoadConversationPort;
import eat.conversation.application.port.out.RetrievePassagesPort;
import eat.conversation.application.port.out.SaveConversationPort;

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
import java.time.Duration;

/**
 * conversation 模組的接線：整個專案唯一同時認識 adapter 和 application 的地方。
 *
 * 看一眼 import 就知道為什麼這種檔案只能待在 eat 這個 package：
 * 上面同時出現了 adapter.out 和 application 的 port ——
 * 這種 import 組合出現在其他任何檔案裡，都代表分層漏了。
 * ArchitectureTest 有兩條規則守著這件事：只有 package eat 和 adapter 自己那一包，
 * 才可以認識 ..adapter.out.reply.llamacpp.. 與 ..adapter.out.knowledge..。
 *
 * 這裡負責的只有一件事：決定「哪個介面用哪個實作」。
 * 目前有三個決定要下 —— GenerateReplyPort 用 llama.cpp 那個實作、
 * RetrievePassagesPort 用向量檢索那個實作、對話紀錄（Load / SaveConversationPort）存 PostgreSQL。
 *
 * 跟 Application 拆開，是因為兩者回答的是不同問題：
 * 那邊回答「怎麼啟動」，這邊回答「誰接誰」。
 * 模組變多時，這邊會一個模組一個 Configuration，那邊永遠只有 main。
 *
 * inbound 那一側（HTTP 入口 ChatController）不在這裡 new：它是個 RestController，
 * 由元件掃描接管。放心讓它自動接的理由是 —— 它只認得 AskQuestionUseCase 這個 port，
 * 不認得任何具體實作，所以「挑實作」的權力還是只在這裡一處。
 *
 * 放在根 package eat 而不是 eat.conversation 裡面，是刻意的：
 * 組裝根必須站在所有模組外面，才有資格認識它們全部。
 *
 * 類別不是 public：它是給 Spring 掃進來的，沒有任何程式該直接引用它。
 *
 * proxyBeanMethods = false：下面每個 Bean 需要的東西都從方法參數收（例如 askQuestion 收
 * 兩個 port），沒有一個 Bean 方法去呼叫另一個 Bean 方法，所以不需要 Spring 用 CGLIB
 * 把這個類別再包一層去攔截那種呼叫。關掉它比較單純，也少一次執行期產生位元組碼。
 */
@Configuration(proxyBeanMethods = false)
class ConversationConfiguration {

    // 這幾個 Bean 就是全部的接線：真正的實作在回傳型別上被換成介面，之後誰也看不到 llama.cpp。
    // 順序不必自己管，Spring 看參數型別就知道誰要先建 ——
    // askQuestion 要兩個 port，容器就會先把下面那兩個建好再餵進來。
    //
    // 兩台模型的位址從 Spring 的 Environment 拿，不再自己讀 System.getProperty：
    // 預設值寫在 application.properties（本機開發），容器裡由 application-docker.properties 覆蓋成服務名稱。
    // Environment 同時涵蓋系統屬性和環境變數，所以 -Dllamacpp.baseUri=...（整合測試在用）
    // 和環境變數 LLAMACPP_BASEURI 都照樣能蓋掉設定檔，優先序由 Spring 管，這裡不必知道值從哪來。
    // 屬性名稱沿用原本的 llamacpp.baseUri，不改成 base-uri：整合測試用同一組名字檢查 server 在不在。

    //
    // 每台模型伺服器各給一個 RestClient：連到哪（base URL）、等多久（逾時）是部署決定，在這裡定；
    // 打哪個路徑、送什麼、錯誤怎麼翻是協定知識，留在 adapter。
    // 這裡沒有一行觀測程式碼：RestClient 從 Spring Boot 注入的 Builder 建，Boot 已經在 Builder 上
    // 掛好觀測，每次呼叫自動有 HTTP span、http.client.requests 指標、請求帶 traceparent 標頭。
    // 自己 RestClient.create() 的話這些全都沒有。

    @Bean
    GenerateReplyPort generateReplyPort(RestClient.Builder restClientBuilder,
                                        ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
                                        @Value("${llamacpp.baseUri}") URI baseUri,
                                        @Value("${llamacpp.connectTimeout}") Duration connectTimeout,
                                        @Value("${llamacpp.readTimeout}") Duration readTimeout) {
        return new LlamaCppGenerateReplyAdapter(
                llamaCppClient(restClientBuilder, requestFactoryBuilder, baseUri, connectTimeout, readTimeout));
    }

    // 要換成別種檢索（全文搜尋、hybrid、加 rerank），動的就只有這個 Bean 這一行 ——
    // AskQuestionService、GroundedQuestion、Conversation、Passage、RetrievePassagesPort、
    // ChatController、GenerateReplyPort 那一側，以及所有測試，一個字都不用改。
    //
    // 換 embedding 供應商也是換這一行，但換的是整個 adapter，不是只換「文字變向量」那一段：
    // 檢索門檻是對 bge-m3 量出來的，換了模型就得重量，所以門檻跟著供應商走。
    //
    // embedding 是另一台 server、另一顆模型（bge-m3），所以是另一個位址。
    // 生成用的 qwen3:8b 不能兼差：它沒被訓練成「向量距離 = 語意相似度」，
    // 而且為了一個向量跑一次 8B 推論貴得離譜。
    @Bean
    RetrievePassagesPort retrievePassagesPort(RestClient.Builder restClientBuilder,
                                              ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
                                              @Value("${llamacpp.embeddingBaseUri}") URI embeddingBaseUri,
                                              @Value("${llamacpp.connectTimeout}") Duration connectTimeout,
                                              @Value("${llamacpp.embeddingReadTimeout}") Duration readTimeout) {
        return new LlamaCppRetrievePassagesAdapter(
                llamaCppClient(restClientBuilder, requestFactoryBuilder, embeddingBaseUri, connectTimeout, readTimeout),
                MarkdownKnowledgeBase.passages());
    }

    // 兩台用同一個做法建，只差位址和逾時。
    //
    // RestClient.Builder 每次注入都是一個新的（Boot 把它設成 prototype），所以兩個 Bean 方法各拿各的，
    // 設了 baseUrl 不會互相污染。
    // requestFactoryBuilder 也用 Boot 給的：底層用哪個 HTTP 函式庫（預設是 JDK 的 HttpClient）
    // 由 spring.http.clients.imperative.factory 統一決定，這裡只疊上這台自己的逾時。
    private static RestClient llamaCppClient(RestClient.Builder restClientBuilder,
                                             ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
                                             URI baseUri, Duration connectTimeout, Duration readTimeout) {
        return restClientBuilder
                .baseUrl(baseUri.toString())
                .requestFactory(requestFactoryBuilder.build(
                        HttpClientSettings.defaults().withTimeouts(connectTimeout, readTimeout)))
                .build();
    }

    // 對話紀錄存 PostgreSQL。一個實作同時當 LoadConversationPort 和 SaveConversationPort：
    // 回傳型別只能寫一個，所以這個 Bean 的型別是實作本身（其他 Bean 都回傳介面，這裡是唯一的例外）；
    // 下面 askQuestion 照樣用兩個 port 介面收它，Spring 看型別就知道同一個物件兩邊都適用。
    // 連線（DataSource）、JdbcClient、交易管理器都是 Spring Boot 依 spring.datasource.* 自動建好的。
    @Bean
    JdbcConversationAdapter conversationStore(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new JdbcConversationAdapter(jdbcClient, new TransactionTemplate(transactionManager));
    }

    @Bean
    AskQuestionUseCase askQuestion(LoadConversationPort loadConversationPort,
                                   RetrievePassagesPort retrievePassagesPort,
                                   GenerateReplyPort generateReplyPort,
                                   SaveConversationPort saveConversationPort) {
        return new AskQuestionService(loadConversationPort, retrievePassagesPort, generateReplyPort,
                saveConversationPort);
    }
}
