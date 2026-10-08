package io.github.andersonchen.talkcal;

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
 * 為什麼用 @SpringBootTest 而不是 CalendarControllerTest 那種 @WebMvcTest：
 * springdoc 的自動設定不在 web 切片裡，切片測試根本看不到 /v3/api-docs。
 * 起整個容器也不必連任何 server —— Spring AI 的 ChatModel 建構時只記下位址，這裡一句話都不說。
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
    @DisplayName("描述了 POST /api/calendar/assistant 與它的請求欄位")
    void describesAssistantEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/calendar/assistant'].post").exists())
                .andExpect(jsonPath("$.components.schemas.AssistantRequest.properties.message").exists());
    }

    @Test
    @DisplayName("助理列出成功、呼叫端送錯、上游出事三種回應，成功和失敗的 body 形狀不同")
    void documentsEveryAssistantOutcome() throws Exception {
        // 這三個碼就是助理的契約：200 正常、400 呼叫端送錯、502 模型或資料庫那頭出事。
        // 少列一個，看文件的人就會以為那種情況不會發生
        String responses = "$.paths['/api/calendar/assistant'].post.responses";
        String schema = ".content['*/*'].schema['$ref']";
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath(responses + "['200']" + schema).value("#/components/schemas/AssistantResponse"))
                .andExpect(jsonPath(responses + "['400']" + schema).value("#/components/schemas/CalendarFailure"))
                .andExpect(jsonPath(responses + "['502']" + schema).value("#/components/schemas/CalendarFailure"));
    }

    @Test
    @DisplayName("描述了行事曆的三個端點，以及各自的成功碼（新增是 201、刪除是 204）")
    void describesCalendarEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/calendar/events'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/calendar/events'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/calendar/events'].get.parameters[?(@.name == 'from')]").exists())
                .andExpect(jsonPath("$.paths['/api/calendar/events/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/calendar/events/{id}'].delete.responses['404']").exists());
    }
}
