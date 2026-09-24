package eat.conversation.adapter.out.knowledge.llamacpp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Adapter 的整合測試：對「真正的」embedding server 跑。
 *
 * 分層跟 chat 那邊一樣：EmbeddingRequest、EmbeddingResponse 各有單元測試，
 * 那是純函式，邏輯在那裡就守完了；這裡只驗「串起來、跟真實世界往返」——
 * HTTP、UTF-8、以及最重要的那件事：這個模型算出來的向量到底有沒有抓到語意。
 *
 * 最後那件事只有對真模型才驗得出來。假 server 回的是我們自己編的數字，
 * 編出來的向量當然「語意正確」—— 那只證明我們會編數字。
 *
 * 需要另一台 server（--embedding 旗標、預設 8081），沒開時整個類別跳過而不是失敗：
 *   llama-server -m bge-m3-Q8_0.gguf --embedding --port 8081
 */
@Tag("integration")
@DisplayName("LlamaCppEmbedText（真實 embedding server）")
class LlamaCppEmbedTextTest {

    // 跟 chat 那台分開：兩個不同的模型、兩個不同的埠
    private static final URI BASE_URI =
            URI.create(System.getProperty("llamacpp.embeddingBaseUri", "http://127.0.0.1:8081"));

    @BeforeAll
    static void requireRunningServer() {
        Assumptions.assumeTrue(isHealthy(),
                () -> "embedding server 沒有在 " + BASE_URI + " 執行，跳過整合測試");
    }

    private static LlamaCppEmbedText adapter() {
        return new LlamaCppEmbedText(BASE_URI);
    }

    @Nested
    @DisplayName("基本往返")
    class RoundTrip {

        @Test
        @DisplayName("回得出固定維度的向量，中文不會壞")
        void returnsFixedSizeVector() {
            float[] vector = adapter().embed("鮭魚富含 Omega-3 脂肪酸");

            assertTrue(vector.length > 0, "向量是空的");
            // 不管文字多長，同一個模型回來的維度永遠一樣 —— 這正是能拿來互相比較的前提
            assertEquals(vector.length, adapter().embed("短").length,
                    "同一個模型對不同長度的文字應該回同樣的維度");
        }

        @Test
        @DisplayName("同樣的文字算兩次，結果一模一樣")
        void isDeterministic() {
            LlamaCppEmbedText adapter = adapter();

            assertArrayEquals(adapter.embed("鮭魚"), adapter.embed("鮭魚"), 0f);
        }
    }

    @Nested
    @DisplayName("向量真的有抓到語意")
    class Semantics {

        @Test
        @DisplayName("換句話說的兩句話，比毫不相干的那句更接近")
        void similarMeaningScoresHigher() {
            LlamaCppEmbedText adapter = adapter();
            float[] 鮭魚營養 = adapter.embed("鮭魚富含 Omega-3 脂肪酸與優質蛋白質");
            float[] 換句話說 = adapter.embed("三文魚有很多好脂肪");
            float[] 不相干 = adapter.embed("今天天氣很好，適合出門散步");

            double 相近 = cosine(鮭魚營養, 換句話說);
            double 無關 = cosine(鮭魚營養, 不相干);

            // 這一題是整個 embedding 路線的存在理由：
            // 「三文魚」和「鮭魚」一個字都沒重疊，關鍵字比對得分是 0，
            // 但它們講的是同一件事 —— 只有向量看得出來
            assertTrue(相近 > 無關,
                    "換句話說應該比不相干更接近，但相近=" + 相近 + " 無關=" + 無關);
        }
    }

    /**
     * 測試自己算一份餘弦，不借用 EmbeddingRetrievePassagesAdapter 那份。
     * 借過來的話，那個方法算錯時這裡也跟著錯，兩邊互相掩護。
     */
    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double lengthA = 0;
        double lengthB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            lengthA += (double) a[i] * a[i];
            lengthB += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(lengthA) * Math.sqrt(lengthB));
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
