package eat.calendar.adapter.out.extraction.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.Category;
import eat.calendar.application.domain.model.EventDescription;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExtractionResponse")
class ExtractionResponseTest {

    // 把模型輸出（內層 JSON）包成 ChatModel 回來的樣子：Spring AI 拆好外層信封之後，就剩這兩樣
    private static ChatResponse response(String content, String finishReason) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content),
                ChatGenerationMetadata.builder().finishReason(finishReason).build())));
    }

    private static ChatResponse response(String content) {
        return response(content, "stop");
    }

    // 大部分測試不在乎原句，用一句涵蓋各種結束時間說法的話當背景
    private static final String SAID = "明天下午三點跟小明吃飯，下週三早上九點到十點半看牙醫，晚上十點到凌晨一點唱歌";

    private static List<CalendarEvent> extract(ChatResponse body) {
        return ExtractionResponse.events(body, new EventDescription(SAID));
    }

    private static List<CalendarEvent> extract(ChatResponse body, String said) {
        return ExtractionResponse.events(body, new EventDescription(said));
    }

    @Test
    @DisplayName("讀出多筆行程（實測的真實輸出）")
    void readsAllEvents() {
        String content = """
                {
                  "events": [
                    { "title": "跟小明吃飯", "start": "2026-10-06T15:00", "end": null, "endSaid": null, "category": "social", "location": null },
                    { "title": "看牙醫", "start": "2026-10-14T09:00", "end": "2026-10-14T10:30", "endSaid": "十點半", "category": "health", "location": "仁愛牙醫" }
                  ]
                }""";

        List<CalendarEvent> events = extract(response(content));

        assertEquals(List.of(
                CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0)).withCategory(Category.SOCIAL),
                new CalendarEvent("看牙醫", LocalDateTime.of(2026, 10, 14, 9, 0), LocalDateTime.of(2026, 10, 14, 10, 30))
                        .withCategory(Category.HEALTH).withDetails("仁愛牙醫", null)),
                events);
    }

    @Test
    @DisplayName("end 是 null：交給 domain 補預設長度")
    void nullEndUsesDomainDefault() {
        CalendarEvent event = extract(
                response("{\"events\":[{\"title\":\"吃飯\",\"start\":\"2026-10-07T12:00\",\"end\":null,\"category\":\"social\",\"location\":null}]}")).getFirst();

        assertEquals(LocalDateTime.of(2026, 10, 7, 13, 0), event.end());
    }

    @Test
    @DisplayName("沒有行程：空清單")
    void noEvents() {
        assertTrue(extract(response("{\"events\":[]}")).isEmpty());
    }

    @Test
    @DisplayName("被 max_tokens 截斷：直接說是截斷，不是說 JSON 壞了")
    void truncatedOutput() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> extract(response("{\"events\":[{\"title\":\"吃", "length")));

        assertTrue(e.getMessage().contains("截斷"));
    }

    @Test
    @DisplayName("模型輸出不是合法 JSON：算上游的錯（IllegalStateException），不是使用者的錯")
    void malformedContentIsAnUpstreamFailure() {
        assertThrows(IllegalStateException.class, () -> extract(response("好的，以下是行程")));
    }

    @Test
    @DisplayName("沒有任何生成結果：算上游的錯")
    void noGenerationIsAnUpstreamFailure() {
        assertThrows(IllegalStateException.class, () -> extract(new ChatResponse(List.of())));
    }

    @Test
    @DisplayName("finish_reason 的大小寫不影響截斷的判斷")
    void truncationIgnoresCase() {
        assertThrows(IllegalStateException.class,
                () -> extract(response("{\"events\":[{\"title\":\"吃", "LENGTH")));
    }

    @Test
    @DisplayName("缺 events 陣列、缺欄位、時間格式不對：都擋下")
    void rejectsWrongShapes() {
        assertThrows(IllegalStateException.class, () -> extract(response("{}")));
        assertThrows(IllegalStateException.class,
                () -> extract(response("{\"events\":[{\"start\":\"2026-10-07T12:00\",\"end\":null,\"category\":\"social\",\"location\":null}]}")));
        assertThrows(IllegalStateException.class,
                () -> extract(response("{\"events\":[{\"title\":\"吃飯\",\"start\":\"明天中午\",\"end\":null,\"category\":\"social\",\"location\":null}]}")));
        assertThrows(IllegalStateException.class,
                () -> extract(response("{\"events\":[{\"title\":\"吃飯\",\"start\":\"2026-10-07T12:00\",\"end\":3,\"category\":\"social\",\"location\":null}]}")));
    }

    @Test
    @DisplayName("模型給了倒著走的行程：domain 的規則擋下，翻成上游的錯")
    void domainViolationBecomesUpstreamFailure() {
        String content = "{\"events\":[{\"title\":\"唱歌\",\"start\":\"2026-10-05T22:00\",\"end\":\"2026-10-05T01:00\",\"endSaid\":\"凌晨一點\",\"category\":\"social\",\"location\":null}]}";

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> extract(response(content)));

        assertTrue(e.getMessage().contains("events[0]"));
        assertTrue(e.getCause() instanceof IllegalArgumentException);
    }

    @Test
    @DisplayName("空白標題：文法擋得了空字串、擋不了空白，domain 會擋")
    void blankTitle() {
        assertThrows(IllegalStateException.class, () -> extract(
                response("{\"events\":[{\"title\":\" \",\"start\":\"2026-10-07T12:00\",\"end\":null,\"category\":\"social\",\"location\":null}]}")));
    }

    @Test
    @DisplayName("分類：協定上的小寫字串對到 domain 的四種分類")
    void mapsEveryCategory() {
        for (var pair : List.of(
                List.of("work", Category.WORK), List.of("personal", Category.PERSONAL),
                List.of("health", Category.HEALTH), List.of("social", Category.SOCIAL))) {
            String content = "{\"events\":[{\"title\":\"事\",\"start\":\"2026-10-07T12:00\",\"end\":null,"
                    + "\"category\":\"" + pair.get(0) + "\",\"location\":null}]}";

            assertEquals(pair.get(1), extract(response(content)).getFirst().category());
        }
    }

    @Test
    @DisplayName("不認得的分類、缺分類：模型壞了，丟 IllegalStateException，不偷偷改成預設")
    void rejectsUnknownOrMissingCategory() {
        assertThrows(IllegalStateException.class, () -> extract(response(
                "{\"events\":[{\"title\":\"事\",\"start\":\"2026-10-07T12:00\",\"end\":null,\"category\":\"holiday\",\"location\":null}]}")));
        assertThrows(IllegalStateException.class, () -> extract(response(
                "{\"events\":[{\"title\":\"事\",\"start\":\"2026-10-07T12:00\",\"end\":null,\"location\":null}]}")));
    }

    @Test
    @DisplayName("地點：有就帶上、null 就沒有；備註永遠沒有（不交給模型）")
    void locationAndNote() {
        CalendarEvent withPlace = extract(response(
                "{\"events\":[{\"title\":\"晨跑\",\"start\":\"2026-10-07T07:00\",\"end\":null,\"category\":\"health\",\"location\":\"河濱公園\"}]}"))
                .getFirst();
        CalendarEvent withoutPlace = extract(response(
                "{\"events\":[{\"title\":\"晨跑\",\"start\":\"2026-10-07T07:00\",\"end\":null,\"category\":\"health\",\"location\":null}]}"))
                .getFirst();

        assertEquals(java.util.Optional.of("河濱公園"), withPlace.location());
        assertTrue(withoutPlace.location().isEmpty());
        assertTrue(withPlace.note().isEmpty());
    }

    @Test
    @DisplayName("地點不是字串也不是 null：擋下")
    void rejectsNonStringLocation() {
        assertThrows(IllegalStateException.class, () -> extract(response(
                "{\"events\":[{\"title\":\"事\",\"start\":\"2026-10-07T12:00\",\"end\":null,\"category\":\"work\",\"location\":42}]}")));
    }

    // ── 結束時間要拿原句核對（使用者回報的 bug：「明天下午3點和 Amy 開會」） ─────────────

    private static ChatResponse oneEvent(String end, String endSaid) {
        String endJson = end == null ? "null" : "\"" + end + "\"";
        String saidJson = endSaid == null ? "null" : "\"" + endSaid + "\"";
        return response("{\"events\":[{\"title\":\"開會\",\"start\":\"2026-10-07T15:00\",\"end\":" + endJson
                + ",\"endSaid\":" + saidJson + ",\"category\":\"work\",\"location\":null}]}");
    }

    @Test
    @DisplayName("回報的 bug：沒講結束時間，模型卻給了跟開始一樣的時間（還抄了開始時間當引用）→ 用預設一小時，不再 502")
    void endEqualToStartWithoutGroundingUsesDefault() {
        CalendarEvent event = extract(oneEvent("2026-10-07T15:00", "下午3點"), "明天下午3點和 Amy 開會").getFirst();

        assertEquals(LocalDateTime.of(2026, 10, 7, 16, 0), event.end());
    }

    @Test
    @DisplayName("模型自己編了一個結束時間（原句沒講）→ 不採用，用預設一小時")
    void inventedEndIsIgnored() {
        CalendarEvent noQuote = extract(oneEvent("2026-10-07T18:00", null), "明天下午3點跟小明吃飯").getFirst();
        CalendarEvent fakeQuote = extract(oneEvent("2026-10-07T18:00", "十點"), "明天下午3點跟小明吃飯").getFirst();

        assertEquals(LocalDateTime.of(2026, 10, 7, 16, 0), noQuote.end());
        assertEquals(LocalDateTime.of(2026, 10, 7, 16, 0), fakeQuote.end());
    }

    @Test
    @DisplayName("原句真的講了結束時間（跟在「到」後面）→ 採用模型給的 end")
    void groundedEndIsUsed() {
        CalendarEvent event = extract(oneEvent("2026-10-07T16:30", "四點半"), "明天下午三點到四點半開會").getFirst();

        assertEquals(LocalDateTime.of(2026, 10, 7, 16, 30), event.end());
    }

    @Test
    @DisplayName("有核對過的結束時間仍然要過 domain 的規則：早於開始照樣擋下（不會因為有引用就放行）")
    void groundedButInvalidEndIsStillRejected() {
        assertThrows(IllegalStateException.class,
                () -> extract(oneEvent("2026-10-07T14:00", "兩點"), "明天下午三點到兩點開會"));
    }

    @Test
    @DisplayName("endIsGrounded：引用要在原句裡，而且緊接在 到／至／~／- 後面")
    void grounding() {
        assertTrue(ExtractionResponse.endIsGrounded("十點半", "下週三早上九點到十點半看牙醫"));
        assertTrue(ExtractionResponse.endIsGrounded("凌晨一點", "晚上十點到凌晨一點唱歌"));
        assertTrue(ExtractionResponse.endIsGrounded("三點半", "週四下午兩點至三點半交報告"));
        assertTrue(ExtractionResponse.endIsGrounded("16:00", "週六 14:00-16:00 打球"));
        assertTrue(ExtractionResponse.endIsGrounded("五點", "明天三點開會到五點"));
        // 模型連「到」一起抄也認得
        assertTrue(ExtractionResponse.endIsGrounded("到十點半", "九點到十點半看牙醫"));

        // 抄成開始時間：前面是「明天」不是「到」
        assertFalse(ExtractionResponse.endIsGrounded("下午3點", "明天下午3點和 Amy 開會"));
        // 原句根本沒有這幾個字
        assertFalse(ExtractionResponse.endIsGrounded("十點", "晚上七點在健身房上瑜珈課"));
        assertFalse(ExtractionResponse.endIsGrounded(null, "明天下午3點開會"));
        assertFalse(ExtractionResponse.endIsGrounded("  ", "明天下午3點開會"));
        assertFalse(ExtractionResponse.endIsGrounded("到", "明天下午3點到公司開會"));
        // 引用裡的特殊字元不會被當成正規表示式
        assertFalse(ExtractionResponse.endIsGrounded(".*", "明天三點到五點"));
    }
}
