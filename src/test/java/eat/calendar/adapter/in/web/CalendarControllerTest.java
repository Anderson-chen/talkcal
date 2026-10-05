package eat.calendar.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.DateRange;
import eat.calendar.application.domain.model.EventDescription;
import eat.calendar.application.domain.model.EventId;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.port.in.AddEventsUseCase;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.ParseEventsUseCase;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * CalendarController 走一遍真的 Spring MVC（理由同 ChatControllerTest：這個類別幾乎每一行都在跟 Spring 講話）。
 *
 * 三個 use case 由一個手寫的假物件同時扮演。假物件照著核心真正的規則演
 * （空白描述丟 IllegalArgumentException、期間交給 DateRange 檢查），測試才不會描述一個不存在的情境。
 */
@WebMvcTest(CalendarController.class)
@Import(CalendarControllerTest.StubConfiguration.class)
@DisplayName("CalendarController")
class CalendarControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StubCalendar calendar;

    @BeforeEach
    void resetStub() {
        calendar.reset();
    }

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0));
    private static final EventId DINNER_ID = new EventId(UUID.fromString("3f1c8a2e-6b0d-4d7e-9a51-2c4e8f7b9d10"));

    @Nested
    @DisplayName("POST /api/calendar/parse")
    class Parse {

        @Test
        @DisplayName("把描述交給 use case，草稿包成 JSON（沒有 id，時間是 ISO 牆上時間）")
        void returnsDrafts() throws Exception {
            mockMvc.perform(post("/api/calendar/parse")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"明天三點跟小明吃飯\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events[0].title").value("跟小明吃飯"))
                    .andExpect(jsonPath("$.events[0].start").value("2026-10-06T15:00:00"))
                    .andExpect(jsonPath("$.events[0].end").value("2026-10-06T16:00:00"))
                    .andExpect(jsonPath("$.events[0].id").doesNotExist());

            assertEquals(List.of("明天三點跟小明吃飯"), calendar.descriptions);
        }

        @Test
        @DisplayName("沒解析出行程：200 加空陣列，不是錯誤")
        void noEvents() throws Exception {
            calendar.parse = description -> List.of();

            mockMvc.perform(post("/api/calendar/parse")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"今天天氣真好\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events").isEmpty());
        }

        @Test
        @DisplayName("描述空白：400")
        void blankText() throws Exception {
            mockMvc.perform(post("/api/calendar/parse")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"  \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }

        @Test
        @DisplayName("模型出事：502")
        void modelFailure() throws Exception {
            calendar.parse = description -> {
                throw new IllegalStateException("呼叫 llama.cpp 失敗");
            };

            mockMvc.perform(post("/api/calendar/parse")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"明天三點跟小明吃飯\"}"))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.error").value("呼叫 llama.cpp 失敗"));
        }

        @Test
        @DisplayName("JSON 本身壞掉：400 而不是 502，也不驚動 use case")
        void malformedJson() throws Exception {
            mockMvc.perform(post("/api/calendar/parse")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\": 這不是 JSON"))
                    .andExpect(status().isBadRequest());

            assertTrue(calendar.descriptions.isEmpty());
        }
    }

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

            // 使用者把結束時間改成 17:30，存進去的就是改過的那個
            assertEquals(List.of(new CalendarEvent("跟小明吃飯",
                    LocalDateTime.of(2026, 10, 6, 15, 0), LocalDateTime.of(2026, 10, 6, 17, 30))), calendar.added);
        }

        @Test
        @DisplayName("使用者在預覽畫面上改壞了（結束早於開始）：400，而且一筆都不存")
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
    @DisplayName("GET /api/calendar/events")
    class ListEvents {

        @Test
        @DisplayName("把期間交給 use case，行程連同 id 包成 JSON")
        void listsEventsInRange() throws Exception {
            mockMvc.perform(get("/api/calendar/events").param("from", "2026-09-28").param("to", "2026-11-09"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events[0].id").value(DINNER_ID.toString()))
                    .andExpect(jsonPath("$.events[0].title").value("跟小明吃飯"));

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
    static final class StubCalendar implements ParseEventsUseCase, AddEventsUseCase, ListEventsUseCase {

        // 預設行為照核心的規則演：描述交給 EventDescription 檢查、期間交給 DateRange 檢查
        private static final Function<String, List<CalendarEvent>> DEFAULT_PARSE = description -> {
            new EventDescription(description);
            return List.of(DINNER);
        };
        private static final Function<DateRange, List<ScheduledEvent>> DEFAULT_LIST =
                range -> List.of(new ScheduledEvent(DINNER_ID, DINNER));

        Function<String, List<CalendarEvent>> parse = DEFAULT_PARSE;
        Function<DateRange, List<ScheduledEvent>> list = DEFAULT_LIST;
        final List<String> descriptions = new ArrayList<>();
        final List<CalendarEvent> added = new ArrayList<>();
        final List<DateRange> ranges = new ArrayList<>();

        @Override
        public List<CalendarEvent> parseEvents(String description) {
            descriptions.add(description);
            return parse.apply(description);
        }

        @Override
        public List<ScheduledEvent> addEvents(List<CalendarEvent> events) {
            added.addAll(events);
            return events.stream().map(event -> new ScheduledEvent(DINNER_ID, event)).toList();
        }

        @Override
        public List<ScheduledEvent> listEvents(LocalDate start, LocalDate endExclusive) {
            DateRange range = new DateRange(start, endExclusive);
            ranges.add(range);
            return list.apply(range);
        }

        void reset() {
            parse = DEFAULT_PARSE;
            list = DEFAULT_LIST;
            descriptions.clear();
            added.clear();
            ranges.clear();
        }
    }
}
