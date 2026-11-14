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
 * Java 的 package 沒有依賴方向：package-private 擋得住別人看見 ExtractionRequest，
 * 擋不住 ParseEventsService 去 import 一個 Spring AI 的 Adapter，編譯和其他測試都不會發現。
 *
 * 每條規則的理由寫在 because(...)，違規時錯誤訊息直接印出來。
 * Javadoc 只寫 because 放不下的事：規則的範圍怎麼切、跟其他規則怎麼分工。
 *
 * 只掃正式程式（DoNotIncludeTests）：Adapter 的測試同時碰 domain 和協定是正常的。
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // 根 package 只有 Application，底下第一層是 app 和各業務模組
    static final String ROOT = "io.github.andersonchen.talkcal";

    // 組裝根：app 裡只有 config（@Configuration）和 job（排程，直接維護某張表）可以 import adapter 的具體類別、
    // 同時 import 好幾個模組。app.observability 不算組裝根：全站共用的觀測不准 import 任何一門業務
    static final String CONFIG = ROOT + ".app.config..";
    static final String JOB = ROOT + ".app.job..";

    @ArchTest
    static final ArchRule coreMustNotDependOnAdapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..")
                    .because("core 定義 port、adapter 實作 port；反過來就失去替換實作的能力");

    @ArchTest
    static final ArchRule domainModelMustStayInnermost =
            noClasses().that().resideInAPackage("..application.domain.model..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..adapter..", "..application.port..", "..application.domain.service..")
                    .because("業務規則不該知道誰在呼叫它，也不該知道資料從哪來");

    /**
     * onlyTheCompositionRootMayKnowOutboundAdapters 也擋得到同一種 import。這條另外留著，錯誤訊息才會講 inbound 該怎麼做。
     */
    @ArchTest
    static final ArchRule inboundAdaptersMustNotDependOnOutboundAdapters =
            noClasses().that().resideInAPackage("..adapter.in..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .because("inbound 只該透過 port 使喚 core，core 才決定要不要呼叫 outbound");

    /**
     * 不點名 adapter.out 底下的子樹（persistence、extraction…）：一個子樹寫一條的話，
     * 新加的子樹（例如 auth 的 security）忘了補規則就不受檢查。
     * adapter.out 裡的類別互相依賴不歸這條管，見 outboundAdapterSubtreesMustNotDependOnEachOther。
     */
    @ArchTest
    static final ArchRule onlyTheCompositionRootMayKnowOutboundAdapters =
            noClasses().that().resideOutsideOfPackages(CONFIG, JOB, "..adapter.out..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
                    .because("用哪一家的實作（PostgreSQL、哪顆模型、S3、哪套密碼雜湊）是組裝時的決定；"
                            + "別的地方一認識具體類別，換實作就不只是改 app.config");

    /**
     * 例如 SQL 和表的長相只在 persistence，日期表、JSON Schema 這些只對某顆模型有效的做法只在 extraction。
     * 兩個子樹要合作，由 core 的 use case 分別呼叫兩個 port。
     * 每個模組的每個子樹各一片：calendar 的 persistence 和 auth 的 persistence 是兩片。
     */
    @ArchTest
    static final ArchRule outboundAdapterSubtreesMustNotDependOnEachOther =
            slices().matching(ROOT + ".(*).adapter.out.(*)..")
                    .should().notDependOnEachOther()
                    .because("一個子樹的細節漏進另一個子樹，換掉其中一家就得改兩包");

    /**
     * 例如 persistence.postgres、security.spring：第二家供應商來的時候放隔壁，不用先搬第一家。
     * 直接放在 adapter.out 底下的類別不屬於任何子樹，outboundAdapterSubtreesMustNotDependOnEachOther 切不到，
     * 每個子樹都能 import，這條規則不准類別放在那裡。
     */
    @ArchTest
    static final ArchRule outboundAdaptersSitUnderAVendorPackage =
            classes().that().resideInAPackage("..adapter.out..")
                    .should().resideInAPackage("..adapter.out.*.*..")
                    .because("供應商各自一包，換一家只動一包；放在子樹或 adapter.out 底下的類別看不出是哪一家的");

    /**
     * 實作 port 的類別和 adapter 內部的零件都在 adapter.out 底下，從 package 看不出差別，只能靠字尾。
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
     * 沒 import adapter，core 自己用 java.net.http 的話，協定細節一樣滲進來。
     */
    @ArchTest
    static final ArchRule coreMustNotTouchHttp =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("java.net..", "javax.net..")
                    .because("怎麼把提問送出去是 adapter 的事，core 只知道有個 port");

    @ArchTest
    static final ArchRule coreMustNotTouchAwsSdk =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("software.amazon..")
                    .because("放在哪一家的物件儲存是 adapter 的事；core 綁上 S3 的型別，就換不掉儲存、也沒法不連 AWS 單獨測");

    @ArchTest
    static final ArchRule coreMustNotDependOnSpring =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("業務規則綁死在框架上，就換不掉框架、也沒法脫離容器單獨測 domain");

    /**
     * 兩個套件都要擋：Jackson 3 把 core、databind 搬到 tools.jackson，註解（@JsonProperty 那些）還在 com.fasterxml.jackson.annotation。
     * 最容易滲進來的是在 domain 欄位上加 @JsonProperty、@JsonIgnore，好讓 CalendarEvent 直接丟給 Jackson。
     * 要序列化，就在 adapter 定義自己的 wire format，例如 CalendarController 的 request、response record。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnJackson =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.fasterxml.jackson..", "tools.jackson..")
                    .because("資料在線路上長什麼樣是 adapter 的事，不該反過來決定 Entity 的形狀");

    /**
     * API 文件的 @Schema 寫在 adapter 自己的 wire format 上，例如 CalendarController.EventFields。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnOpenApi =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage("io.swagger..", "org.springdoc..")
                    .because("API 文件描述的是線路格式，屬於 inbound adapter，不該滲進業務規則");

    /**
     * 斷路器、bulkhead 包在 adapter 裡，見 GuardedChatModel。
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnResilience4j =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("io.github.resilience4j..")
                    .because("容錯是呼叫外部依賴時的事，屬於 adapter；core 綁上它，之後就沒辦法交給平台（service mesh）處理");

    /**
     * 根 package 底下第一層各一片，app 也是一片。從組裝根出發的依賴不算，app.observability 沒有這個豁免。
     * 一個模組要用另一個模組的能力，在自己模組定義 outbound port，由 app.config 接上對方的 use case；
     * 例子見 SignedIn（calendar 不認得 auth，也拿得到登入者）。AI 助理之後要拆成自己的模組，見 AssistantMemoryArchive。
     */
    @ArchTest
    static final ArchRule modulesMustNotDependOnEachOther =
            slices().matching(ROOT + ".(*)..")
                    .should().notDependOnEachOther()
                    .ignoreDependency(resideInAnyPackage(CONFIG, JOB), alwaysTrue())
                    .because("模組直接互相 import，就再也沒辦法單獨改動、單獨拆出去其中一個");

    @ArchTest
    static final ArchRule packagesMustBeFreeOfCycles =
            // 每個模組裡的每個 package 各一片，只看模組內部；模組之間見 modulesMustNotDependOnEachOther
            slices().matching(ROOT + ".(*).(**)")
                    .should().beFreeOfCycles()
                    .because("繞成環的兩個 package 實際上是同一個，拆開只是假象");
}
