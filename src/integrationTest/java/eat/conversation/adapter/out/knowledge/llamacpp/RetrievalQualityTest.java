package eat.conversation.adapter.out.knowledge.llamacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.conversation.adapter.out.knowledge.MarkdownKnowledgeBase;
import eat.conversation.application.domain.model.Passage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * 檢索品質：對真的 embedding server、真的知識庫、正式用的地板與相對門檻。
 *
 * LlamaCppRetrievePassagesAdapterTest 裡那些手寫向量的測試驗的是「拿到向量之後怎麼挑」——
 * 排序、兩道關卡、topK，那些是純邏輯，手寫向量就守得完。這一組驗的是只有真模型才答得出來的問題：
 * 那兩個門檻，對真實的問法到底合不合用。
 *
 * 所以這裡不架假 server，理由跟 LlamaCppGenerateReplyAdapterTest 寫的一樣：
 * 假 server 回的向量是我們自己編的，編出來的當然「語意正確」——
 * 那只證明我們會編數字。
 *
 * 順帶也守住了「文字變向量」那一段跟真實世界的往返：
 * 中文送不過去（UTF-8 壞掉）、每次回的維度不一樣（cosineSimilarity 會直接炸）、
 * 向量沒抓到語意（三文魚那題），這幾種壞法在這裡都會紅。
 *
 * 這六題就是當初用來訂那兩個數字的量測樣本。把它們留成測試，
 * 之後有人調門檻、換 embedding 模型、或改知識庫內容，就會被擋下來 ——
 * 那兩個數字是調參不是定理，沒有測試守著，調壞了不會有人發現。
 *
 * 實測到的分數分布（bge-m3 加這份知識庫）：
 *   不相干的問題    0.27 ~ 0.32
 *   相關但問得抽象  0.50 ~ 0.56
 *   問得具體        0.76 ~ 0.79
 * 地板 0.40 取在前兩段中間那片空白，相對門檻 0.9 負責在有訊號時擋掉
 * 「同一份文件但不同主題」的段落。
 *
 * 原本是 LlamaCppRetrievePassagesAdapterTest 裡的一個 @Nested。整合測試改成用目錄分開之後，
 * 一個檔案只能待在 src/test 或 src/integrationTest 其中一邊，所以拆出來自成一個類別。
 * 名字不沿用 adapter 的名字：同一個 package 在兩個 source set 各放一個同名類別，IDE 裡搜尋會分不清。
 */
@DisplayName("LlamaCppRetrievePassagesAdapter（真實 server）的檢索品質")
class RetrievalQualityTest {

    // 跟正式程式讀同一個系統屬性，才不會一邊指到別台、一邊還在檢查本機那台
    private static final URI BASE_URI =
            URI.create(System.getProperty("llamacpp.embeddingBaseUri", "http://127.0.0.1:8081"));

    // 健康檢查一次就夠，不必每題打一次，所以在類別載入時查好存起來
    private static final boolean SERVER_UP = isHealthy();

    @BeforeEach
    void requireRunningServer() {
        // 跟 LlamaCppGenerateReplyAdapterTest 同一套：server 沒開時「跳過」而不是「失敗」——
        // 環境沒準備好不等於程式壞了，這兩件事必須分得開，
        // 否則紅燈很快就會被當成背景雜訊。
        //
        // 但這裡不能照抄它的 @BeforeAll：這個類別只有 @ParameterizedTest，
        // 在 @BeforeAll 就中止的話，參數化測試根本不會展開成一題一題，JUnit 回報的是「0 個測試」。
        // 單獨執行這個檔案時（IntelliJ 按 ▶ 就是這樣），Gradle 會把 0 個當成
        // 「No tests found」而變紅 —— 正好是上面說要避免的事。
        // 放在 @BeforeEach，每一題先展開、再各自回報 SKIPPED
        Assumptions.assumeTrue(SERVER_UP,
                () -> "embedding server 沒有在 " + BASE_URI + " 執行，跳過檢索品質測試");
    }

    private LlamaCppRetrievePassagesAdapter retrieval() {
        return new LlamaCppRetrievePassagesAdapter(RestClient.builder().baseUrl(BASE_URI.toString()).build(), MarkdownKnowledgeBase.passages());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            // 問得具體，用詞跟片段幾乎重疊
            "鮭魚要煎幾分鐘？,         鮭魚.md > 烹調建議",
            "雞胸肉有多少蛋白質？,     雞胸肉.md > 營養成分",
            // 同義詞：「三文魚」跟「鮭魚」一個字都沒重疊，關鍵字比對得分是 0
            "三文魚有什麼營養？,       鮭魚.md > 營養成分",
            // 抽象問法：一個字都對不上，整排分數掉到 0.5 以下。
            // 這題正是「絕對門檻 0.5」會誤殺、換成地板 0.40 才救得回來的那一題
            "深海魚對身體有什麼好處？, 鮭魚.md > 營養成分",
    })
    @DisplayName("撈得到，而且第一段是對的")
    void retrievesTheRightPassage(String question, String expectedSource) {
        List<Passage> found = retrieval().retrievePassages(question);

        assertTrue(!found.isEmpty(), "什麼都沒檢索到：" + question);
        assertEquals(expectedSource, found.get(0).source(),
                "最相關的那段不對，實際撈到：" + found);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"巴黎鐵塔有多高？", "如何學習 Java？"})
    @DisplayName("知識庫沒有的東西：回空清單，不硬塞不相干的片段")
    void returnsNothingForUnrelatedQuestion(String question) {
        // 硬塞比不給更糟：GroundedQuestion 那句「只依資料回答」會反咬一口，
        // 模型會拿著雞胸肉的資料說「參考資料中沒有提到巴黎鐵塔」
        assertEquals(List.of(), retrieval().retrievePassages(question));
    }

    private static boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(BASE_URI.resolve("/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
