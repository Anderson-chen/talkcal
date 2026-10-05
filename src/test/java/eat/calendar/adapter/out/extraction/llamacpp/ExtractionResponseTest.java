package eat.calendar.adapter.out.extraction.llamacpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.calendar.application.domain.model.CalendarEvent;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ExtractionResponse")
class ExtractionResponseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // 把模型輸出（內層 JSON）包進 llama.cpp 回應的外層信封，跟實測的形狀一樣
    private static String response(String content, String finishReason) {
        return JSON.writeValueAsString(java.util.Map.of(
                "choices", List.of(java.util.Map.of(
                        "finish_reason", finishReason,
                        "index", 0,
                        "message", java.util.Map.of("role", "assistant", "content", content)))));
    }

    private static String response(String content) {
        return response(content, "stop");
    }

    @Test
    @DisplayName("讀出多筆行程（實測的真實輸出）")
    void readsAllEvents() {
        String content = """
                {
                  "events": [
                    { "title": "跟小明吃飯", "start": "2026-10-06T15:00", "end": null },
                    { "title": "看牙醫", "start": "2026-10-14T09:00", "end": "2026-10-14T10:30" }
                  ]
                }""";

        List<CalendarEvent> events = ExtractionResponse.events(response(content));

        assertEquals(List.of(
                CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0)),
                new CalendarEvent("看牙醫", LocalDateTime.of(2026, 10, 14, 9, 0), LocalDateTime.of(2026, 10, 14, 10, 30))),
                events);
    }

    @Test
    @DisplayName("end 是 null：交給 domain 補預設長度")
    void nullEndUsesDomainDefault() {
        CalendarEvent event = ExtractionResponse.events(
                response("{\"events\":[{\"title\":\"吃飯\",\"start\":\"2026-10-07T12:00\",\"end\":null}]}")).getFirst();

        assertEquals(LocalDateTime.of(2026, 10, 7, 13, 0), event.end());
    }

    @Test
    @DisplayName("沒有行程：空清單")
    void noEvents() {
        assertTrue(ExtractionResponse.events(response("{\"events\":[]}")).isEmpty());
    }

    @Test
    @DisplayName("llama.cpp 回 error 物件：帶出 server 自己的訊息")
    void serverError() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ExtractionResponse.events("{\"error\":{\"code\":400,\"message\":\"failed to parse grammar\"}}"));

        assertTrue(e.getMessage().contains("failed to parse grammar"));
    }

    @Test
    @DisplayName("被 max_tokens 截斷：直接說是截斷，不是說 JSON 壞了")
    void truncatedOutput() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ExtractionResponse.events(response("{\"events\":[{\"title\":\"吃", "length")));

        assertTrue(e.getMessage().contains("截斷"));
    }

    @Test
    @DisplayName("模型輸出不是合法 JSON：算上游的錯（IllegalStateException），不是使用者的錯")
    void malformedContentIsAnUpstreamFailure() {
        assertThrows(IllegalStateException.class, () -> ExtractionResponse.events(response("好的，以下是行程")));
    }

    @Test
    @DisplayName("外層回應不是合法 JSON：同樣算上游的錯")
    void malformedEnvelopeIsAnUpstreamFailure() {
        assertThrows(IllegalStateException.class, () -> ExtractionResponse.events("<html>502</html>"));
    }

    @Test
    @DisplayName("缺 events 陣列、缺欄位、時間格式不對：都擋下")
    void rejectsWrongShapes() {
        assertThrows(IllegalStateException.class, () -> ExtractionResponse.events(response("{}")));
        assertThrows(IllegalStateException.class,
                () -> ExtractionResponse.events(response("{\"events\":[{\"start\":\"2026-10-07T12:00\",\"end\":null}]}")));
        assertThrows(IllegalStateException.class,
                () -> ExtractionResponse.events(response("{\"events\":[{\"title\":\"吃飯\",\"start\":\"明天中午\",\"end\":null}]}")));
        assertThrows(IllegalStateException.class,
                () -> ExtractionResponse.events(response("{\"events\":[{\"title\":\"吃飯\",\"start\":\"2026-10-07T12:00\",\"end\":3}]}")));
    }

    @Test
    @DisplayName("模型給了倒著走的行程：domain 的規則擋下，翻成上游的錯")
    void domainViolationBecomesUpstreamFailure() {
        String content = "{\"events\":[{\"title\":\"唱歌\",\"start\":\"2026-10-05T22:00\",\"end\":\"2026-10-05T01:00\"}]}";

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ExtractionResponse.events(response(content)));

        assertTrue(e.getMessage().contains("events[0]"));
        assertTrue(e.getCause() instanceof IllegalArgumentException);
    }

    @Test
    @DisplayName("空白標題：文法擋得了空字串、擋不了空白，domain 會擋")
    void blankTitle() {
        assertThrows(IllegalStateException.class, () -> ExtractionResponse.events(
                response("{\"events\":[{\"title\":\" \",\"start\":\"2026-10-07T12:00\",\"end\":null}]}")));
    }
}
