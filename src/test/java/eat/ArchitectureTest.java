package eat;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 把架構規則寫成會失敗的測試。
 *
 * 為什麼需要這個檔案：Java 的 package 沒有依賴方向的概念。
 * package-private 擋得住「別人看見 ChatRequest 這個 class」，
 * 但擋不住 AskQuestionService 去 import 一個 llama.cpp 的 Adapter ——
 * 那樣寫編譯器一聲不吭，測試照樣全綠，架構就是這樣一點一點爛掉的。
 *
 * 規則全部寫成 noClasses(...)，也就是「不准出現」的形式。
 * 每條都帶 because(...)，違規時錯誤訊息會直接說出理由，
 * 不必回來翻這個檔案才知道為什麼不行。
 *
 * 只掃正式程式（DoNotIncludeTests）：測試本來就要能碰到所有層，
 * 例如 Adapter 的測試同時碰 domain 和協定，那是合理的。
 */
@AnalyzeClasses(packages = "eat", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * 最重要的一條：依賴方向只能由外往內。
     * 這正是目前唯一沒人守、而且實測會通過編譯的漏洞。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnAdapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..")
                    .because("core 定義 port、adapter 實作 port；反過來就失去替換實作的能力");

    /**
     * domain model 是最內圈，連自己家的 port 和 service 都不該認識。
     * Conversation 和 Reply 只該依賴 Java 標準函式庫。
     */
    @ArchTest
    static final ArchRule domainModelMustStayInnermost =
            noClasses().that().resideInAPackage("..application.domain.model..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..adapter..", "..application.port..", "..application.domain.service..")
                    .because("業務規則不該知道誰在呼叫它，也不該知道資料從哪來");

    /**
     * 兩側的 adapter 互不認識。
     * 入口那側（目前是 HTTP 的 ChatController）換成別的、llama.cpp 換成 OpenAI，都不該牽動另一側。
     */
    @ArchTest
    static final ArchRule inboundAdaptersMustNotDependOnOutboundAdapters =
            noClasses().that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .because("inbound 只該透過 port 使喚 core，core 才決定要不要呼叫 outbound");

    /**
     * 只有組裝根可以認識具體的 llama.cpp 實作。
     * 這條守住的是「換一行 new 就能換掉 LLM」這個承諾 ——
     * 一旦有第二個地方 import 它，那個承諾就破了。
     */
    @ArchTest
    static final ArchRule onlyTheCompositionRootMayKnowLlamaCpp =
            noClasses().that().resideOutsideOfPackages("eat", "..adapter.out.llamacpp..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.llamacpp..")
                    .because("挑選實作是組裝時的決定，散到別處就等於把供應商焊死在程式裡");

    /**
     * core 不准碰 HTTP。
     * 這條是前一條的補充：就算沒有 import adapter，
     * 只要 core 自己開始用 java.net.http，協定細節一樣滲進來了。
     */
    @ArchTest
    static final ArchRule onlyTheCompositionRootMayKnowTheRetrievalAdapter =
            noClasses().that().resideOutsideOfPackages("eat", "..adapter.out.knowledge..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.knowledge..")
                    .because("關鍵字比對只是第一版檢索，之後要換成 embedding；"
                            + "讓它漏進 core 或別的 adapter，那一換就會牽一髮動全身");

    @ArchTest
    static final ArchRule coreMustNotTouchHttp =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("java.net..", "javax.net..")
                    .because("怎麼把提問送出去是 adapter 的事，core 只知道有個 port");

    /**
     * core 不准依賴 Spring。這條是「導入 Spring」這件事的護欄。
     *
     * Spring 是組裝時的框架 —— 它負責把 bean 兜起來、把 web 請求接進來，這些都是最外圈的事。
     * 一旦 @Component、@Autowired、@Service 爬進 application/domain，業務規則就跟框架焊死了：
     * 想單獨用純 JUnit 測一個 Conversation 得先起半個容器，想換框架得改動核心。
     * 允許 Spring 待在 adapter 和組裝根（那本來就是它的地盤），但到 core 的門口為止。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnSpring =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("業務規則綁死在框架上，就換不掉框架、也沒法脫離容器單獨測 domain");

    /**
     * core 不准依賴 Jackson。跟上一條同一個道理，只是換成序列化函式庫。
     *
     * 這條擋的是最常見的那種滲透：為了讓 Conversation 能直接丟給 Jackson 序列化，
     * 在 domain 的欄位上加 @JsonProperty、@JsonIgnore。那看起來只是「加個註解」，
     * 實際上是讓「資料怎麼在線路上呈現」這件事跑進了業務規則裡 ——
     * 從此改一個 JSON 欄位名要動 Entity，而 Entity 的形狀開始被外部格式牽著走。
     * 要序列化就在 adapter 自己定義一份 wire format（ChatRequest.Body 就是這樣做的）。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnJackson =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                    .because("資料在線路上長什麼樣是 adapter 的事，不該反過來決定 Entity 的形狀");

    /**
     * package 之間不可以繞成環。
     * 循環依賴是「這兩包其實分不開」的訊號，而且會讓任何一邊都無法單獨測試。
     */
    @ArchTest
    static final ArchRule packagesMustBeFreeOfCycles =
            slices().matching("eat.conversation.(**)")
                    .should().beFreeOfCycles()
                    .because("繞成環的兩個 package 實際上是同一個，拆開只是假象");
}
