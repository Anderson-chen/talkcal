package eat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 產出來的 OpenAPI 文件，真的長成我們以為的樣子。
 *
 * 為什麼用 @SpringBootTest 而不是 ChatControllerTest 那種 @WebMvcTest：
 * springdoc 的自動設定不在 web 切片裡，切片測試根本看不到 /v3/api-docs。
 * 起整個容器也不必連任何 server —— 兩個 llama.cpp adapter 建構時只記下位址，
 * 檢索的索引也是第一次提問才建，這裡一題都不問。
 * 資料庫也一樣：連線池要等第一次查詢才真的連，只有 Flyway 會在啟動時就連上去建表，
 * 所以把 Flyway 關掉（spring.flyway.enabled=false），這個測試就不需要 PostgreSQL。
 *
 * 只驗「文件有沒有描述到契約」：路徑、欄位、各種狀態碼。
 * 版面長怎樣是 Swagger UI 的事，不值得測。
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
@DisplayName("OpenAPI 文件")
class OpenApiDocumentationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("描述了 POST /api/chat 與它的請求欄位")
    void describesChatEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/chat'].post").exists())
                .andExpect(jsonPath("$.components.schemas.Request.properties.question").exists());
    }

    @Test
    @DisplayName("列出成功、呼叫端送錯、對話不存在、對話被同時更新、上游出事五種回應")
    void documentsEveryOutcome() throws Exception {
        // 這五個碼就是 ChatController 的契約：200 正常、400 呼叫端送錯、404 對話不存在、
        // 409 同一段對話被同時問了兩題、502 模型（或檢索、資料庫）那頭出事。
        // 少列一個，看文件的人就會以為那種情況不會發生。
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/chat'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/chat'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/chat'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/chat'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/chat'].post.responses['502']").exists());
    }

    @Test
    @DisplayName("成功與失敗的 body 形狀不同：200 是 Response、錯誤是 Failure")
    void distinguishesSuccessAndFailureBodies() throws Exception {
        String responses = "$.paths['/api/chat'].post.responses";
        String schema = ".content['*/*'].schema['$ref']";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(responses + "['200']" + schema).value("#/components/schemas/Response"))
                .andExpect(jsonPath(responses + "['400']" + schema).value("#/components/schemas/Failure"))
                .andExpect(jsonPath(responses + "['502']" + schema).value("#/components/schemas/Failure"));
    }
}
