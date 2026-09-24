package eat;

import eat.conversation.adapter.out.knowledge.KeywordRetrievePassagesAdapter;
import eat.conversation.adapter.out.knowledge.SampleKnowledgeBase;
import eat.conversation.adapter.out.reply.llamacpp.LlamaCppGenerateReplyAdapter;
import eat.conversation.application.domain.service.AskQuestionService;
import eat.conversation.application.port.in.AskQuestionUseCase;
import eat.conversation.application.port.out.GenerateReplyPort;
import eat.conversation.application.port.out.RetrievePassagesPort;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

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
 * 目前有兩個決定要下 —— GenerateReplyPort 用 llama.cpp 那個實作、
 * RetrievePassagesPort 用關鍵字比對那個實作。兩個都只是一行 new。
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

    // 預設打本機常駐的那台，需要時用 -Dllamacpp.baseUri=... 指到別的位址，不必改程式。
    // （之後這類設定會搬到 application.properties / @ConfigurationProperties，這步先維持原樣，一次只換一件事。）
    private static final URI LLAMA_CPP_BASE_URI =
            URI.create(System.getProperty("llamacpp.baseUri", "http://127.0.0.1:8080"));

    // 這幾個 Bean 就是全部的接線：真正的實作在回傳型別上被換成介面，之後誰也看不到 llama.cpp。
    // 順序不必自己管，Spring 看參數型別就知道誰要先建 ——
    // askQuestion 要兩個 port，容器就會先把下面那兩個建好再餵進來。

    @Bean
    GenerateReplyPort generateReplyPort() {
        return new LlamaCppGenerateReplyAdapter(LLAMA_CPP_BASE_URI);
    }

    // 換成 embedding 檢索時，動的只有這一個 Bean ——
    // AskQuestionService、GroundedQuestion、Conversation、Passage、
    // ChatController、GenerateReplyPort 那一側，一個字都不用改。
    @Bean
    RetrievePassagesPort retrievePassagesPort() {
        return new KeywordRetrievePassagesAdapter(SampleKnowledgeBase.passages());
    }

    @Bean
    AskQuestionUseCase askQuestion(RetrievePassagesPort retrievePassagesPort,
                                   GenerateReplyPort generateReplyPort) {
        return new AskQuestionService(retrievePassagesPort, generateReplyPort);
    }
}
