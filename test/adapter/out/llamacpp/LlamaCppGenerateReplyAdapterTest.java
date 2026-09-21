package adapter.out.llamacpp;

import application.domain.model.Conversation;
import application.domain.model.Reply;
import application.domain.service.AskQuestionService;
import application.port.in.AskQuestionUseCase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static application.domain.model.Conversation.Role.USER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adapter 的整合測試：對「真正的」llama-server 跑。
 *
 * 這個 package 的測試分兩層：
 * Json、JsonParser、ChatRequest、ChatResponse 各自有單元測試，
 * 那是純函式，邏輯在那裡就守完了；
 * Adapter 本身扣掉那些零件之後，剩下的責任只有「把它們串起來，並跟真實世界往返」——
 * HTTP、編碼、逾時、失敗包裝，全都是只有對真東西講話才驗得出來的事。
 *
 * 所以這裡不架假 server。假 server 是我們自己寫的，
 * 如果我們對協定的理解本來就是錯的，那個誤解會同時出現在 Adapter 和假 server 裡，
 * 互相掩護，測試照樣全綠 —— 而真的 llama.cpp 不會配合我們。
 *
 * 因為依賴外部環境，server 沒開時整個類別會被「跳過」而不是「失敗」——
 * 環境沒準備好不等於程式壞了，這兩件事必須分得開，否則紅燈很快就會被當成背景雜訊。
 *
 * 跟專案其他測試一樣用 Test 結尾；「需要外部環境」這件事由 @Tag("integration") 標示，
 * IntelliJ 的執行設定可以用它過濾掉這些慢測試。
 */
@Tag("integration")
@DisplayName("LlamaCppGenerateReplyAdapter（真實 server）")
class LlamaCppGenerateReplyAdapterTest {

    // 預設打本機常駐的那台，需要時可以用 -Dllamacpp.baseUri=... 指到別的位址
    private static final URI BASE_URI =
            URI.create(System.getProperty("llamacpp.baseUri", "http://127.0.0.1:8080"));

    @BeforeAll
    static void requireRunningServer() {
        Assumptions.assumeTrue(isHealthy(),
                () -> "llama-server 沒有在 " + BASE_URI + " 執行，跳過整合測試");
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

    private static LlamaCppGenerateReplyAdapter adapter() {
        return new LlamaCppGenerateReplyAdapter(BASE_URI);
    }

    private static List<Conversation.Message> question(String text) {
        return List.of(new Conversation.Message(USER, text));
    }

    @Test
    @DisplayName("真的 server 接受我們組出來的 body，而且回覆可以用")
    void realServerAcceptsOurRequest() {
        // 這題的價值不在模型答了什麼，而在「沒有丟例外」——
        // 代表 ChatRequest 的格式被真的 llama.cpp 接受，ChatResponse 也讀得懂它的回應
        Reply reply = adapter().generateReply(Optional.empty(), question("現在幾月???"));

        assertFalse(reply.text().isBlank(), "回覆不該是空的");
    }

    @Test
    @DisplayName("思考過程不會混進回覆裡")
    void doesNotLeakThinkingIntoReply() {
        // Qwen3 是 thinking 模型，server 會把思考過程分離到 reasoning_content。
        // 萬一哪天 server 版本改了行為、或 chat template 壞掉，
        // 標籤殘片就會混進 content —— 這題守住那條線
        Reply reply = adapter().generateReply(Optional.empty(), question("2+2 等於多少？"));

        assertFalse(reply.text().contains("<think>"), "回覆混進了思考標籤：" + reply.text());
        assertFalse(reply.text().contains("</think>"), "回覆混進了思考標籤：" + reply.text());
    }

    @Test
    @DisplayName("系統指令真的生效，中文原封往返")
    void appliesSystemInstructionAndRoundTripsChinese() {
        // 用英文提問、要求用繁體中文回答：
        // 回覆裡出現中文，就同時證明了 system 訊息有被套用、而且 UTF-8 兩個方向都沒壞
        Reply reply = adapter().generateReply(
                Optional.of("你只能用繁體中文回答，不要使用英文"),
                question("What is the capital of Japan?"));

        assertTrue(containsChinese(reply.text()), "回覆裡沒有中文：" + reply.text());
    }

    @Test
    @DisplayName("對話歷史真的有送出去，模型記得前一輪")
    void carriesConversationHistory() {
        // 走完整條鏈：UseCase -> Service -> Port -> Adapter -> 真的 server。
        // 第二輪不重複提那個數字，模型還答得出來，就證明歷史確實被攤平送出
        AskQuestionUseCase useCase = new AskQuestionService(adapter());
        Conversation conversation = Conversation.start("你是簡潔的助理，用繁體中文回答");

        useCase.askQuestion(conversation, "請記住這個數字：7777。只要回覆「好」就行。");
        Reply second = useCase.askQuestion(conversation, "我剛才請你記住的數字是多少？");

        assertTrue(second.text().contains("7777"),
                "模型沒有記住前一輪的數字，歷史可能沒送出去：" + second.text());
        // 順便確認 Conversation 的狀態：兩問兩答，而且沒有懸著的提問
        assertTrue(conversation.messages().size() == 4, "應該有兩問兩答");
        assertFalse(conversation.awaitingReply(), "不該還有待回覆的提問");
    }

    private static boolean containsChinese(String text) {
        return text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
    }
}
