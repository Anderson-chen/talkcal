package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.DateRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.IdempotencyKey;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ProposalId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.AddedEvents;
import io.github.andersonchen.talkcal.calendar.application.port.in.IdempotencyKeyReusedException;
import io.github.andersonchen.talkcal.calendar.application.port.in.EventNotFoundException;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.in.RemoveEventUseCase;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CalendarController 走一遍真的 Spring MVC：這個類別幾乎每一行都在跟 Spring 講話
 * （路由、JSON 綁定、狀態碼、例外翻譯），不起 MockMvc 就等於沒測。
 *
 * 三個 use case 由一個手寫的假物件同時扮演。假物件照著核心真正的規則演
 * （期間交給 DateRange 檢查、不存在的 id 丟 EventNotFoundException），測試才不會描述一個不存在的情境。
 */
@WebMvcTest(CalendarController.class)
@Import(CalendarControllerTest.StubConfiguration.class)
@DisplayName("CalendarController")
class CalendarControllerTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;

    /**
     * 這裡測的是 controller 本身（路由、JSON、狀態碼），不是認證：每個請求預設都是「已經登入、帶著 CSRF token」。
     * 沒登入回 401、沒帶 CSRF 回 403 這些規則，在 SecurityTest 對真的 SecurityFilterChain 測。
     */
    @BeforeEach
    void signedIn() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user(SIGNED_IN.toString())).with(csrf()))
                .build();
    }

    @Autowired
    StubCalendar calendar;

    @BeforeEach
    void resetStub() {
        calendar.reset();
    }

    private static final OwnerId SIGNED_IN = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0))
                    .withCategory(Category.SOCIAL).withDetails("拉麵店", null);
    private static final EventId DINNER_ID = new EventId(UUID.fromString("3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10"));

    @Nested
    @DisplayName("POST /api/calendar/events")
    class Add {

        @Test
        @DisplayName("把草稿（原樣或改過）存起來：201，每筆帶 id")
        void savesAndReturnsIds() throws Exception {
            mockMvc.perform(post("/api/calendar/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"events":[{"title":"跟小明吃飯","start":"2026-10-06T15:00","end":"2026-10-06T17:30"}]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.events[0].id").value(DINNER_ID.toString()))
                    .andExpect(jsonPath("$.events[0].end").value("2026-10-06T17:30:00"));

            // 使用者把結束時間改成 17:30，存進去的就是改過的那個；沒帶分類就是 domain 的預設
            assertEquals(List.of(new CalendarEvent("跟小明吃飯",
                    LocalDateTime.of(2026, 10, 6, 15, 0), LocalDateTime.of(2026, 10, 6, 17, 30))), calendar.added);
            assertEquals(Category.DEFAULT, calendar.added.getFirst().category());
        }

        @Test
        @DisplayName("使用者在確認卡片上改壞了（結束早於開始）：400，而且一筆都不存")
        void editedIntoInvalidIsRejected() throws Exception {
            mockMvc.perform(post("/api/calendar/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"events":[
                                      {"title":"跟小明吃飯","start":"2026-10-06T15:00","end":"2026-10-06T16:00"},
                                      {"title":"唱歌","start":"2026-10-06T22:00","end":"2026-10-06T01:00"}
                                    ]}
                                    """))
                    .andExpect(status().isBadRequest());

            assertTrue(calendar.added.isEmpty(), "有一筆不合規就不該呼叫 use case");
        }

        @Test
        @DisplayName("缺欄位、events 是 null、陣列裡夾 null：都是 400，不是 500")
        void missingPartsAreBadRequest() throws Exception {
            for (String body : List.of(
                    "{\"events\":[{\"title\":\"吃飯\",\"start\":\"2026-10-06T15:00\"}]}",
                    "{}",
                    "{\"events\":[null]}")) {
                mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
            assertTrue(calendar.added.isEmpty());
        }

        @Test
        @DisplayName("時間格式不對：400（Spring 讀不懂 JSON）")
        void badDateTimeFormat() throws Exception {
            mockMvc.perform(post("/api/calendar/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"events\":[{\"title\":\"吃飯\",\"start\":\"明天三點\",\"end\":\"明天四點\"}]}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/calendar/events：從助理卡片來的行程（產品指標 #223）")
    class AddFromProposal {

        private static final String CARD = "9b0f3c2e-1111-4a5b-8c7d-000000000001";

        @Test
        @DisplayName("帶著 proposalId 的那幾筆，把卡片 id 轉給 use case 核對；沒帶的不轉")
        void passesProposalIdsThrough() throws Exception {
            mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"events":[
                                      {"title":"吃飯","start":"2026-10-06T15:00","end":"2026-10-06T16:00","proposalId":"%s"},
                                      {"title":"看牙醫","start":"2026-10-07T09:00","end":"2026-10-07T10:00"}
                                    ]}
                                    """.formatted(CARD)))
                    .andExpect(status().isCreated());

            assertEquals(List.of(List.of(ProposalId.of(CARD))), calendar.adopting);
        }

        @Test
        @DisplayName("proposalId 不是 UUID：400，一筆都不存")
        void badProposalIdIsRejected() throws Exception {
            mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"events":[{"title":"吃飯","start":"2026-10-06T15:00","end":"2026-10-06T16:00","proposalId":"abc"}]}
                                    """))
                    .andExpect(status().isBadRequest());
            assertTrue(calendar.added.isEmpty());
        }
    }

    @Nested
    @DisplayName("POST /api/calendar/events：分類、地點、備註")
    class AddWithDetails {

        @Test
        @DisplayName("分類、地點、備註原樣存進去，空白的備註當成沒有")
        void savesDetails() throws Exception {
            mockMvc.perform(post("/api/calendar/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"events":[{"title":"晨跑","start":"2026-10-07T07:00","end":"2026-10-07T08:00",
                                                "category":"HEALTH","location":"河濱公園","note":"  "}]}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.events[0].category").value("HEALTH"))
                    .andExpect(jsonPath("$.events[0].location").value("河濱公園"))
                    .andExpect(jsonPath("$.events[0].note").doesNotExist());

            CalendarEvent saved = calendar.added.getFirst();
            assertEquals(Category.HEALTH, saved.category());
            assertEquals(Optional.of("河濱公園"), saved.location());
            assertEquals(Optional.empty(), saved.note());
        }

        @Test
        @DisplayName("不認得的分類（小寫、不存在的類）：400，不偷偷改成預設")
        void unknownCategoryIsBadRequest() throws Exception {
            for (String category : List.of("work", "HOLIDAY")) {
                mockMvc.perform(post("/api/calendar/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"events\":[{\"title\":\"開會\",\"start\":\"2026-10-07T10:00\","
                                        + "\"end\":\"2026-10-07T11:00\",\"category\":\"" + category + "\"}]}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(category)));
            }
            assertTrue(calendar.added.isEmpty());
        }
    }

    @Nested
    @DisplayName("DELETE /api/calendar/events/{id}")
    class Remove {

        @Test
        @DisplayName("刪掉了：204，沒有 body")
        void removes() throws Exception {
            mockMvc.perform(delete("/api/calendar/events/" + DINNER_ID))
                    .andExpect(status().isNoContent());

            assertEquals(List.of(DINNER_ID), calendar.removed);
        }

        @Test
        @DisplayName("沒有這個行程：404")
        void notFound() throws Exception {
            String missing = "00000000-0000-0000-0000-000000000000";

            mockMvc.perform(delete("/api/calendar/events/" + missing))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("id 不是 UUID：400，不驚動 use case")
        void malformedId() throws Exception {
            mockMvc.perform(delete("/api/calendar/events/not-a-uuid"))
                    .andExpect(status().isBadRequest());

            assertTrue(calendar.removed.isEmpty());
        }
    }

    @Nested
    @DisplayName("GET /api/calendar/events")
    class ListEvents {

        @Test
        @DisplayName("把期間交給 use case，行程連同 id 包成 JSON")
        void listsEventsInRange() throws Exception {
            mockMvc.perform(get("/api/calendar/events").param("from", "2026-09-28").param("to", "2026-11-09"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events[0].id").value(DINNER_ID.toString()))
                    .andExpect(jsonPath("$.events[0].title").value("跟小明吃飯"))
                    .andExpect(jsonPath("$.events[0].category").value("SOCIAL"));

            assertEquals(List.of(new DateRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 11, 9))), calendar.ranges);
        }

        @Test
        @DisplayName("期間不合規（倒過來、太長）：400")
        void invalidRange() throws Exception {
            mockMvc.perform(get("/api/calendar/events").param("from", "2026-11-09").param("to", "2026-09-28"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/calendar/events").param("from", "2026-01-01").param("to", "2027-01-01"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("缺參數、格式不是 yyyy-MM-dd：400（Spring 自己擋），不驚動 use case")
        void missingOrMalformedParameters() throws Exception {
            mockMvc.perform(get("/api/calendar/events").param("from", "2026-09-28"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/api/calendar/events").param("from", "9/28").param("to", "11/9"))
                    .andExpect(status().isBadRequest());

            assertTrue(calendar.ranges.isEmpty());
        }

        @Test
        @DisplayName("資料庫出事：502")
        void databaseFailure() throws Exception {
            calendar.list = range -> {
                throw new IllegalStateException("讀取行程失敗");
            };

            mockMvc.perform(get("/api/calendar/events").param("from", "2026-09-28").param("to", "2026-11-09"))
                    .andExpect(status().isBadGateway());
        }
    }

    /**
     * Idempotency-Key header（#51）：controller 只負責把 header 交給 use case、把結果翻成狀態碼和 Idempotent-Replayed。
     * 「同一個鍵真的只存一次」在 JdbcCalendarEventAdapterTest 對真的 PostgreSQL 測。
     */
    @Nested
    @DisplayName("POST /api/calendar/events 帶 Idempotency-Key")
    class IdempotentAdd {

        private static final String DINNER_JSON =
                "{\"events\":[{\"title\":\"吃飯\",\"start\":\"2026-10-06T15:00\",\"end\":\"2026-10-06T16:00\"}]}";
        // 一看就是測試值，而且不到 10 個字：gitleaks 的 generic-api-key 會把「名字有 key、值 10 個字以上」的字串當成真的 key
        // （長得像 UUID 的、test-idempotency-key-1 都被抓過，PR #156 的 secrets 檢查紅燈）
        private static final String IDEMPOTENCY_KEY = "test-key";

        @Test
        @DisplayName("第一次：鍵交給 use case，201，Idempotent-Replayed: false")
        void firstTime() throws Exception {
            mockMvc.perform(post("/api/calendar/events").header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(DINNER_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "false"))
                    .andExpect(jsonPath("$.events[0].id").value(DINNER_ID.toString()));

            assertEquals(List.of(new IdempotencyKey(IDEMPOTENCY_KEY)), calendar.keys);
        }

        @Test
        @DisplayName("重送：一樣是 201、一樣的內容，多了 Idempotent-Replayed: true")
        void replay() throws Exception {
            for (String replayed : List.of("false", "true")) {
                mockMvc.perform(post("/api/calendar/events").header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON).content(DINNER_JSON))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotent-Replayed", replayed))
                        .andExpect(jsonPath("$.events[0].id").value(DINNER_ID.toString()));
            }
        }

        @Test
        @DisplayName("同一個鍵、不同內容：422，說明要換一個鍵")
        void sameKeyOtherContent() throws Exception {
            mockMvc.perform(post("/api/calendar/events").header("Idempotency-Key", IDEMPOTENCY_KEY)
                    .contentType(MediaType.APPLICATION_JSON).content(DINNER_JSON));

            mockMvc.perform(post("/api/calendar/events").header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"events\":[{\"title\":\"開會\",\"start\":\"2026-10-06T15:00\",\"end\":\"2026-10-06T16:00\"}]}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("換一個新的鍵")));
        }

        @Test
        @DisplayName("反例：鍵的格式不對（有空白、超過 100 字）是 400，什麼都不存")
        void malformedKey() throws Exception {
            for (String bad : List.of("has space", "a".repeat(101))) {
                mockMvc.perform(post("/api/calendar/events").header("Idempotency-Key", bad)
                                .contentType(MediaType.APPLICATION_JSON).content(DINNER_JSON))
                        .andExpect(status().isBadRequest());
            }
            assertTrue(calendar.added.isEmpty());
        }

        @Test
        @DisplayName("不帶鍵：照舊每次都存（走沒有鍵的那個 use case 方法），回應沒有 Idempotent-Replayed")
        void withoutKey() throws Exception {
            for (int i = 0; i < 2; i++) {
                mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON).content(DINNER_JSON))
                        .andExpect(status().isCreated())
                        .andExpect(header().doesNotExist("Idempotent-Replayed"));
            }
            assertEquals(2, calendar.added.size());
            assertTrue(calendar.keys.isEmpty());
        }

        @Test
        @DisplayName("帶鍵時，卡片的 proposalId 一樣轉給 use case（採納指標照算）")
        void passesProposalIdsWithKey() throws Exception {
            String card = "9b0f3c2e-1111-4a5b-8c7d-000000000001";
            mockMvc.perform(post("/api/calendar/events").header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"events":[{"title":"吃飯","start":"2026-10-06T15:00","end":"2026-10-06T16:00","proposalId":"%s"}]}
                                    """.formatted(card)))
                    .andExpect(status().isCreated());

            assertEquals(List.of(List.of(ProposalId.of(card))), calendar.adopting);
        }
    }

    @Nested
    @DisplayName("#104 誰的行事曆")
    class Owner {

        @Test
        @DisplayName("新增、查詢、刪除都交給 use case 登入者的帳號 ID：請求裡沒有任何「擁有者」參數可以改")
        void ownerIsTheSignedInUser() throws Exception {
            mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"events\":[{\"title\":\"吃飯\",\"start\":\"2026-10-06T15:00\",\"end\":\"2026-10-06T16:00\"}]}"))
                    .andExpect(status().isCreated());
            mockMvc.perform(get("/api/calendar/events").param("from", "2026-09-28").param("to", "2026-11-09"))
                    .andExpect(status().isOk());
            mockMvc.perform(delete("/api/calendar/events/" + DINNER_ID))
                    .andExpect(status().isNoContent());

            assertEquals(List.of(SIGNED_IN, SIGNED_IN, SIGNED_IN), calendar.owners);
        }

        @Test
        @DisplayName("換一個人登入，交給 use case 的就是那個人")
        void anotherUser() throws Exception {
            String other = "3f1c8a2e-0000-4000-8000-000000000002";

            mockMvc.perform(get("/api/calendar/events").with(user(other)).param("from", "2026-09-28").param("to", "2026-11-09"))
                    .andExpect(status().isOk());

            assertEquals(List.of(OwnerId.of(other)), calendar.owners);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        StubCalendar calendar() {
            return new StubCalendar();
        }
    }

    /**
     * 一個假物件同時扮演三個 use case：記下收到什麼，每題可以換掉行為。
     */
    static final class StubCalendar implements AddEventsUseCase, ListEventsUseCase, RemoveEventUseCase {

        // 預設行為照核心的規則演：期間交給 DateRange 檢查
        private static final Function<DateRange, List<ScheduledEvent>> DEFAULT_LIST =
                range -> List.of(new ScheduledEvent(DINNER_ID, DINNER));

        Function<DateRange, List<ScheduledEvent>> list = DEFAULT_LIST;
        final List<OwnerId> owners = new ArrayList<>();
        final List<CalendarEvent> added = new ArrayList<>();
        final List<List<ProposalId>> adopting = new ArrayList<>();
        final List<DateRange> ranges = new ArrayList<>();
        final List<EventId> removed = new ArrayList<>();
        final List<IdempotencyKey> keys = new ArrayList<>();
        // 照真正的 use case 演：同一個鍵第二次來，內容一樣就是重送、不一樣就丟例外
        private final Map<IdempotencyKey, List<CalendarEvent>> seen = new HashMap<>();

        @Override
        public List<ScheduledEvent> addEvents(OwnerId owner, List<CalendarEvent> events, List<ProposalId> adopting) {
            owners.add(owner);
            added.addAll(events);
            this.adopting.add(adopting);
            return events.stream().map(event -> new ScheduledEvent(DINNER_ID, event)).toList();
        }

        @Override
        public AddedEvents addEvents(OwnerId owner, IdempotencyKey key, List<CalendarEvent> events, List<ProposalId> adopting) {
            owners.add(owner);
            keys.add(key);
            this.adopting.add(adopting);
            List<CalendarEvent> earlier = seen.putIfAbsent(key, events);
            if (earlier != null && !earlier.equals(events)) {
                throw new IdempotencyKeyReusedException(key);
            }
            if (earlier == null) {
                added.addAll(events);
            }
            return new AddedEvents(events.stream().map(event -> new ScheduledEvent(DINNER_ID, event)).toList(), earlier != null);
        }

        @Override
        public List<ScheduledEvent> listEvents(OwnerId owner, LocalDate start, LocalDate endExclusive) {
            owners.add(owner);
            DateRange range = new DateRange(start, endExclusive);
            ranges.add(range);
            return list.apply(range);
        }

        // 只有 DINNER_ID 存在；其他 id 照真正的 use case 那樣丟「找不到」
        @Override
        public void removeEvent(OwnerId owner, EventId id) {
            owners.add(owner);
            if (!DINNER_ID.equals(id)) {
                throw new EventNotFoundException(id);
            }
            removed.add(id);
        }

        void reset() {
            list = DEFAULT_LIST;
            owners.clear();
            added.clear();
            adopting.clear();
            ranges.clear();
            removed.clear();
            keys.clear();
            seen.clear();
        }
    }
}
