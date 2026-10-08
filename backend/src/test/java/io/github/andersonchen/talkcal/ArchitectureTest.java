package io.github.andersonchen.talkcal;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 把架構規則寫成會失敗的測試。
 *
 * 為什麼需要這個檔案：Java 的 package 沒有依賴方向的概念。
 * package-private 擋得住「別人看見 ExtractionRequest 這個 class」，
 * 但擋不住 ParseEventsService 去 import 一個 Spring AI 的 Adapter ——
 * 那樣寫編譯器一聲不吭，測試照樣全綠，架構就是這樣一點一點爛掉的。
 *
 * 規則全部寫成 noClasses(...)，也就是「不准出現」的形式。
 * 每條都帶 because(...)，違規時錯誤訊息會直接說出理由，
 * 不必回來翻這個檔案才知道為什麼不行。
 *
 * 只掃正式程式（DoNotIncludeTests）：測試本來就要能碰到所有層，
 * 例如 Adapter 的測試同時碰 domain 和協定，那是合理的。
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // 根 package：只有 Application。底下是 app（組裝根，代表整個應用程式）和各業務模組（calendar）
    static final String ROOT = "io.github.andersonchen.talkcal";

    // app 裡唯一可以認識 adapter 具體類別、也可以同時認識好幾個模組的兩個 package：
    //   app.config —— 所有 @Configuration（誰接誰）
    //   app.job    —— 排程工作（直接做某張表的維護，例如清舊對話）
    // app.observability 也在 app 裡，但它是全站共用的觀測，不需要、也不准認識任何一門業務
    static final String CONFIG = ROOT + ".app.config..";
    static final String JOB = ROOT + ".app.job..";

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
     * CalendarEvent 這些只該依賴 Java 標準函式庫。
     */
    @ArchTest
    static final ArchRule domainModelMustStayInnermost =
            noClasses().that().resideInAPackage("..application.domain.model..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..adapter..", "..application.port..", "..application.domain.service..")
                    .because("業務規則不該知道誰在呼叫它，也不該知道資料從哪來");

    /**
     * 兩側的 adapter 互不認識。
     * 入口那側（目前是 HTTP 的 controller 和 AI 助理的工具）換成別的、模型換成別家，都不該牽動另一側。
     */
    @ArchTest
    static final ArchRule inboundAdaptersMustNotDependOnOutboundAdapters =
            noClasses().that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .because("inbound 只該透過 port 使喚 core，core 才決定要不要呼叫 outbound");

    /**
     * 只有組裝根裡的 app.config、app.job 可以認識具體的實作。下面兩條是同一句話套在 adapter.out 的兩個子樹上。
     *
     * adapter.out 底下每個 package 就是「某一種往外的實作們」：
     * persistence 放 Save / Load / DeleteEventPort 的（和助理的對話記憶）、extraction 放 ExtractEventsPort 的，
     * 供應商一律再往下一層（persistence.postgres、extraction.springai）。
     *
     * 守住的是「換一行 new 就能換掉實作」這個承諾 —— 一旦有第二個地方 import 它，那個承諾就破了。
     */
    @ArchTest
    static final ArchRule onlyTheCompositionRootMayKnowThePersistenceAdapters =
            noClasses().that().resideOutsideOfPackages(CONFIG, JOB, "..adapter.out.persistence..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.persistence..")
                    .because("存在哪（PostgreSQL、別的資料庫、記憶體）是組裝時的決定；"
                            + "SQL 和表的長相只該出現在 persistence 這一包");

    // ExtractEventsPort 的實作們（目前只有 Spring AI 那個）
    @ArchTest
    static final ArchRule onlyTheCompositionRootMayKnowTheExtractionAdapters =
            noClasses().that().resideOutsideOfPackages(CONFIG, JOB, "..adapter.out.extraction..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out.extraction..")
                    .because("用哪個模型解析行程是組裝時的決定；日期表、JSON Schema 這些只對某顆模型有效的手法，"
                            + "只該出現在 extraction 這一包");

    /**
     * Adapter 這個字尾是有意義的，不是隨手加的裝飾。
     *
     * adapter 圈裡有兩種類別：一種實作 core 的 outbound port（換掉它 core 無感），
     * 另一種是 adapter 內部的零件（例如 ExtractionRequest、ExtractionResponse 這些協定翻譯）。
     * 兩種都在 adapter.out 底下，從 package 看不出差別 —— 所以用字尾區分，並用這條規則守著。
     */
    @ArchTest
    static final ArchRule adapterSuffixIsReservedForPortImplementations =
            classes().that().resideInAPackage("..adapter.out..")
                    .and().haveSimpleNameEndingWith("Adapter")
                    .should().dependOnClassesThat().resideInAPackage("..application.port.out..")
                    .because("Adapter 這個字尾在這個專案裡專指「實作 core 的某個 outbound port」；"
                            + "adapter 圈內部的零件（例如 ExtractionRequest、ExtractionResponse）不叫 Adapter，"
                            + "不然從名字看不出它站在哪一層");

    /**
     * core 不准碰 HTTP。
     * 這條是前面那幾條的補充：就算沒有 import adapter，
     * 只要 core 自己開始用 java.net.http，協定細節一樣滲進來了。
     */
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
     * 想單獨用純 JUnit 測一個 CalendarEvent 得先起半個容器，想換框架得改動核心。
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
     * 兩個套件都要擋：Jackson 3 把 core/databind 搬到 tools.jackson，
     * 但註解（@JsonProperty 那些）刻意留在 com.fasterxml.jackson.annotation 沒動。
     *
     * 這條擋的是最常見的那種滲透：為了讓 CalendarEvent 能直接丟給 Jackson 序列化，
     * 在 domain 的欄位上加 @JsonProperty、@JsonIgnore。那看起來只是「加個註解」，
     * 實際上是讓「資料怎麼在線路上呈現」這件事跑進了業務規則裡 ——
     * 從此改一個 JSON 欄位名要動 Entity，而 Entity 的形狀開始被外部格式牽著走。
     * 要序列化就在 adapter 自己定義一份 wire format（CalendarController 的 request / response record 就是這樣做的）。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnJackson =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..", "tools.jackson..")
                    .because("資料在線路上長什麼樣是 adapter 的事，不該反過來決定 Entity 的形狀");

    /**
     * core 不准依賴 OpenAPI 的註解。跟 Jackson 那條同一個道理，只是換成 API 文件。
     *
     * @Schema、@Operation 描述的是「HTTP 上長什麼樣」，那是 controller 這種 adapter 的事。
     * 一旦為了讓文件好看就在 CalendarEvent 上加 @Schema，domain 就開始替某一種協定打扮了。
     * 要寫文件，就寫在 adapter 自己的 wire format（例如 CalendarController.EventFields）上。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnOpenApi =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.swagger..", "org.springdoc..")
                    .because("API 文件描述的是線路格式，屬於 inbound adapter，不該滲進業務規則");

    /**
     * 模組之間互不認識：不同的業務各自一個模組。
     *
     * 目前只有 calendar 一個模組（一般問答的 conversation 模組已經拿掉），這條暫時不會擋到東西。
     * 留著是因為 AI 助理遲早要拆成自己的模組（見 AssistantMemoryCleanup 的註解），拆開那天它就開始守門。
     *
     * ROOT.(*).. 把根 package 底下第一層的 package 各切成一片（一個模組一片），app 也是一片。
     * app 裡的 config、job 是組裝根，本來就要認識各個模組 —— 從它們出發的依賴不算；
     * app.observability 沒有這個豁免：它是全站共用的觀測，同樣不准認識任何一門業務。
     *
     * 哪天一個模組需要另一個模組的能力，該做的是在自己模組裡定義一個 outbound port，
     * 由 app.config 接上另一個模組的 use case，而不是直接 import 對方的類別。
     */
    @ArchTest
    static final ArchRule modulesMustNotDependOnEachOther =
            slices().matching(ROOT + ".(*)..")
                    .should().notDependOnEachOther()
                    .ignoreDependency(resideInAnyPackage(CONFIG, JOB), alwaysTrue())
                    .because("模組直接互相 import，就再也沒辦法單獨改動、單獨拆出去其中一個");

    /**
     * package 之間不可以繞成環。
     * 循環依賴是「這兩包其實分不開」的訊號，而且會讓任何一邊都無法單獨測試。
     */
    @ArchTest
    static final ArchRule packagesMustBeFreeOfCycles =
            // ROOT.(*).(**)：每個模組裡的每個 package 各切一片。
            // 模組之間的依賴由上面的 modulesMustNotDependOnEachOther 管，這條只看模組內部
            slices().matching(ROOT + ".(*).(**)")
                    .should().beFreeOfCycles()
                    .because("繞成環的兩個 package 實際上是同一個，拆開只是假象");
}
