package eat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.conversation.adapter.out.knowledge.MarkdownKnowledgeBase;

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
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

/**
 * 檢索品質：對真的 embedding server、真的知識庫、正式用的 topK 與相似度門檻。
 *
 * 測的是 ConversationConfiguration.knowledgeBase 組出來的那個 retriever 本身 ——
 * 門檻、topK、SimpleVectorStore 都跟正式環境同一份，這裡只自己建 EmbeddingModel（不起 Spring 容器）。
 * 所以放在根 package eat：組裝根的 Bean 方法是 package-private 的。
 *
 * 不架假 server：假 server 回的向量是我們自己編的，編出來的當然「語意正確」—— 那只證明我們會編數字。
 *
 * 順帶也守住了「文字變向量」那一段跟真實世界的往返：
 * 中文送不過去（UTF-8 壞掉）、向量沒抓到語意（三文魚那題），這幾種壞法在這裡都會紅。
 *
 * 這六題就是當初用來訂門檻的量測樣本。把它們留成測試，
 * 之後有人調門檻、換 embedding 模型、或改知識庫內容，就會被擋下來 ——
 * 門檻是調參不是定理，沒有測試守著，調壞了不會有人發現。
 *
 * 實測到的分數分布（bge-m3 加這份知識庫）：
 *   不相干的問題    0.27 ~ 0.32
 *   相關但問得抽象  0.50 ~ 0.56
 *   問得具體        0.76 ~ 0.79
 * 門檻 0.40 取在前兩段中間那片空白。
 * 以前還有一道「相對門檻 0.9」擋掉同一份文件但不同主題的段落；改用 Spring AI 的 retriever 之後拿掉了，
 * 所以這裡只斷言「第一段是對的」，不斷言只撈到一段。
 */
@DisplayName("知識庫檢索（真實 embedding server）的品質")
class RetrievalQualityTest {

    // 健康檢查用。跟 application.properties 的 spring.ai.openai.embedding.base-url 預設值一致
    private static final URI BASE_URI =
            URI.create(System.getProperty("spring.ai.openai.embedding.base-url", "http://127.0.0.1:8081/v1"));

    // 健康檢查一次就夠，不必每題打一次，所以在類別載入時查好存起來
    private static final boolean SERVER_UP = isHealthy();

    @BeforeEach
    void requireRunningServer() {
        // server 沒開時「跳過」而不是「失敗」—— 環境沒準備好不等於程式壞了。
        //
        // 放在 @BeforeEach 而不是 @BeforeAll：這個類別只有 @ParameterizedTest，
        // 在 @BeforeAll 就中止的話，參數化測試根本不會展開成一題一題，JUnit 回報的是「0 個測試」，
        // 單獨執行這個檔案時 Gradle 會把它當成「No tests found」而變紅。
        Assumptions.assumeTrue(SERVER_UP,
                () -> "embedding server 沒有在 " + BASE_URI + " 執行，跳過檢索品質測試");
    }

    private static DocumentRetriever knowledgeBase() {
        // 設定跟 application.properties 的 spring.ai.openai.embedding.* 一致，
        // 包括 metadata-mode=none：不然算向量時會多接一行 metadata，跟量門檻時的文字不一樣
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .options(OpenAiEmbeddingOptions.builder()
                        .baseUrl(BASE_URI.toString())
                        .apiKey("not-used-by-llama-server")
                        .model("bge-m3")
                        .timeout(Duration.ofSeconds(30))
                        .maxRetries(0)
                        .build())
                .metadataMode(MetadataMode.NONE)
                .build();
        return new ConversationConfiguration().knowledgeBase(embeddingModel);
    }

    private static List<Document> retrieve(String question) {
        return knowledgeBase().retrieve(new Query(question));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            // 問得具體，用詞跟片段幾乎重疊
            "鮭魚要煎幾分鐘？,         鮭魚.md > 烹調建議",
            "雞胸肉有多少蛋白質？,     雞胸肉.md > 營養成分",
            // 同義詞：「三文魚」跟「鮭魚」一個字都沒重疊，關鍵字比對得分是 0
            "三文魚有什麼營養？,       鮭魚.md > 營養成分",
            // 抽象問法：一個字都對不上，整排分數掉到 0.5 以下。
            // 這題正是「門檻 0.5」會誤殺、換成 0.40 才救得回來的那一題
            "深海魚對身體有什麼好處？, 鮭魚.md > 營養成分",
    })
    @DisplayName("撈得到，而且第一段是對的")
    void retrievesTheRightPassage(String question, String expectedSource) {
        List<Document> found = retrieve(question);

        assertTrue(!found.isEmpty(), "什麼都沒檢索到：" + question);
        assertEquals(expectedSource, found.getFirst().getMetadata().get(MarkdownKnowledgeBase.SOURCE),
                "最相關的那段不對，實際撈到：" + found);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"巴黎鐵塔有多高？", "如何學習 Java？"})
    @DisplayName("知識庫沒有的東西：回空清單，不硬塞不相干的片段")
    void returnsNothingForUnrelatedQuestion(String question) {
        // 硬塞比不給更糟：「只依資料回答」那句話會反咬一口，
        // 模型會拿著雞胸肉的資料說「參考資料中沒有提到巴黎鐵塔」
        assertEquals(List.of(), retrieve(question));
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
