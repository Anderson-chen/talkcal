package eat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.conversation.adapter.in.web.ChatController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 系統測試：把整個應用啟起來，用真的 HTTP 打真的端點，對真的模型。
 *
 * 跟其他測試的分工：
 * - ChatControllerTest 是 web 切片，只載入 controller、use case 用假的 —— 驗協定翻譯。
 * - LlamaCpp*AdapterTest 各自對一台 server —— 驗某一個 adapter 跟真實世界的往返。
 * - 這裡驗的是「這些東西接起來之後，整個應用到底能不能用」。
 *
 * 關鍵在於這個測試一個具體 adapter 都不認識：它只打 POST /api/chat。
 * 接線是 ConversationConfiguration 決定的，所以它測的永遠是「應用現在的樣子」——
 * 哪天把檢索的 @Bean 從關鍵字換成 embedding，同一題自動變成兩台 server 的端到端，
 * 這個檔案一個字都不用改。連接線本身有沒有接錯，也一起驗到了。
 *
 * 放在根 package eat：它測的是整個應用，不屬於任何一個模組 —— 跟 ArchitectureTest 同一個理由。
 * 名字留了 System 是因為同一個端點已經有一個 ChatControllerTest，兩者必須分得開；
 * 「需要外部環境」這件事還是由 @Tag("integration") 標示，預設的 ./gradlew test 不會跑到。
 *
 * RANDOM_PORT 而不是固定 8090：那個埠常常已經被你自己跑著的應用佔住了。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@DisplayName("Chat API（整個應用 + 真模型）")
class ChatApiSystemTest {

    // 跟正式程式讀同一個系統屬性，才不會一邊指到別台、一邊還在檢查本機那台
    private static final URI LLAMA_CPP_BASE_URI =
            URI.create(System.getProperty("llamacpp.baseUri", "http://127.0.0.1:8080"));

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeAll
    static void requireRunningServer() {
        // 這個檢查刻意是 static 的 @BeforeAll：它會在 Spring 容器被載入之前跑，
        // server 沒開就整個類別跳過，不必先花時間把應用啟起來才發現沒東西可測
        Assumptions.assumeTrue(isHealthy(),
                () -> "llama-server 沒有在 " + LLAMA_CPP_BASE_URI + " 執行，跳過系統測試");
    }

    private ResponseEntity<ChatController.Response> ask(String question) {
        // 用 controller 自己的 record 當請求/回應型別：JSON 的形狀由它定義，
        // 測試就不必再手寫一份可能跟它不同步的 JSON 字串
        return restTemplate.postForEntity("/api/chat",
                new ChatController.Request(question), ChatController.Response.class);
    }

    @Test
    @DisplayName("問知識庫裡有的事，回覆帶得出資料裡的數字")
    void answersFromKnowledgeBase() {
        ResponseEntity<ChatController.Response> response =
                ask("雞胸肉每 100 公克有多少蛋白質？");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        String reply = response.getBody().reply();
        // 這個斷言只能算弱訊號：模型自己可能也知道雞胸肉的蛋白質含量，
        // 所以「答對了」不完全等於「檢索有效」。真正證明 grounding 的是下面兩層 ——
        // GroundedQuestionTest 驗 prompt 組得對，adapter 的整合測試驗檢索撈對片段。
        // 這一題的價值在於「整條線接起來會動、不會炸、回得出東西」。
        assertTrue(reply.contains("31"), "回覆裡沒有資料裡的 31 公克：" + reply);
    }

    @Test
    @DisplayName("問知識庫沒有的事，檢索空手而回也照樣答得出來")
    void answersWithoutRetrieval() {
        ResponseEntity<ChatController.Response> response = ask("巴黎鐵塔有多高？");

        // 沒檢索到片段時 GroundedQuestion 送的就是原始提問，模型自由發揮。
        // 驗的是「這條路不會炸」，不是答案內容
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(!response.getBody().reply().isBlank(), "回覆是空的");
    }

    @Test
    @DisplayName("空白提問回 400，而且根本不必驚動模型")
    void rejectsBlankQuestion() {
        // 這題在 ChatControllerTest 也有，但那是切片測試裡用假 use case 演出來的。
        // 這裡驗的是真的接起來之後，那條規則還在原位 ——
        // 提問在 GroundedQuestion 就被擋下，所以這題秒回，不會等模型
        ResponseEntity<String> response =
                restTemplate.postForEntity("/api/chat", new ChatController.Request("  "), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    private static boolean isHealthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(LLAMA_CPP_BASE_URI.resolve("/health"))
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
