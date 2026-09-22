package eat;

import eat.conversation.adapter.out.llamacpp.LlamaCppGenerateReplyAdapter;
import eat.conversation.application.domain.service.AskQuestionService;
import eat.conversation.application.port.in.AskQuestionUseCase;
import eat.conversation.application.port.out.GenerateReplyPort;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.net.URI;

/**
 * 組裝根（Composition Root）：整個專案唯一同時認識 adapter 和 application 的地方。
 *
 * 看一眼 import 就知道為什麼它只能有一個：
 * 上面同時出現了 adapter.out 和 application 的 port ——
 * 這種 import 組合在其他任何檔案裡出現，都代表分層漏了。
 *
 * 這裡負責的只有一件事：決定「哪個介面用哪個實作」。
 * 目前只有一個決定要下 —— GenerateReplyPort 用 llama.cpp 那個實作。
 * 要換成 OpenAI 或 Claude，改的是下面 generateReplyPort() 那一個 @Bean，
 * AskQuestionService、Conversation、ChatController、所有測試一個字都不用動。
 *
 * inbound 那一側（HTTP 入口 ChatController）不在這裡 new：它是個 @RestController，
 * 由 Spring 元件掃描接管。放心讓它自動接的理由是 —— 它只認得 AskQuestionUseCase 這個 port，
 * 不認得任何具體實作，所以「挑實作」的權力還是只在這裡一處。
 *
 * Spring 只待在這一圈。往內的 application/domain 全是純 Java，連一個 @Component 都沒有 ——
 * ArchitectureTest 有一條規則守著這件事：core 不准依賴 org.springframework。
 *
 * 放在根 package（eat）而不是某個功能模組裡面，是刻意的：
 * 功能模組是 eat 底下的子 package（目前只有 conversation），組裝根必須站在所有模組外面
 * 才有資格認識它們全部；@SpringBootApplication 的元件掃描也正是以這個 package 為根往下掃。
 * 這個位置也正是 Spring Modulith 用來偵測模組的起點，將來要接它不必再搬一次。
 *
 * proxyBeanMethods = false：下面每個 @Bean 需要的東西都從方法參數收（例如 askQuestion 收
 * GenerateReplyPort），沒有一個 @Bean 去呼叫另一個 @Bean 方法，所以不需要 Spring 用 CGLIB
 * 把這個類別再包一層去攔截那種呼叫。關掉它比較單純，也少一次執行期產生位元組碼。
 */
@SpringBootApplication(proxyBeanMethods = false)
public class Application {

    // 預設打本機常駐的那台，需要時用 -Dllamacpp.baseUri=... 指到別的位址，不必改程式。
    // （之後這類設定會搬到 application.properties / @ConfigurationProperties，這步先維持原樣，一次只換一件事。）
    private static final URI LLAMA_CPP_BASE_URI =
            URI.create(System.getProperty("llamacpp.baseUri", "http://127.0.0.1:8080"));

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    // 這兩個 @Bean 就是全部的接線：真正的實作在回傳型別上被換成介面，之後誰也看不到 llama.cpp。
    // 順序不必自己管，Spring 看參數型別就知道誰要先建 ——
    // askQuestion 要一個 GenerateReplyPort，容器就會先把下面這個 @Bean 建好再餵進來。

    @Bean
    GenerateReplyPort generateReplyPort() {
        return new LlamaCppGenerateReplyAdapter(LLAMA_CPP_BASE_URI);
    }

    @Bean
    AskQuestionUseCase askQuestion(GenerateReplyPort generateReplyPort) {
        return new AskQuestionService(generateReplyPort);
    }
}
