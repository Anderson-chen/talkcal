package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.Category;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.domain.model.ScheduledEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.service.ListArchivedEventsService;
import io.github.andersonchen.talkcal.calendar.application.port.in.ListArchivedEventsUseCase;
import io.github.andersonchen.talkcal.calendar.application.port.out.EventArchivePort;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ArchiveController 走一遍真的 Spring MVC（#54、#274）：日期、月份怎麼綁，範圍不合規回 400，儲存出事回 502，擁有者只從登入者來。
 *
 * 查看封存用正式的 ListArchivedEventsService，只把物件儲存換成手寫的假物件：範圍的規則（400）是真的那一套，
 * 假的歸檔記下被要了誰的、哪幾個月。對話的還原也由假物件扮演，記下被叫了幾次、拿到什麼參數。
 */
@WebMvcTest(ArchiveController.class)
@Import(ArchiveControllerTest.StubConfiguration.class)
@DisplayName("ArchiveController")
class ArchiveControllerTest {

    private static final OwnerId SIGNED_IN = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");
    private static final OwnerId SOMEONE_ELSE = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000002");

    private static final ScheduledEvent DENTIST = new ScheduledEvent(
            new EventId(UUID.fromString("9b2d6f4e-0000-4000-8000-000000000001")),
            new CalendarEvent("看牙醫", LocalDateTime.of(2024, 3, 5, 9, 0), LocalDateTime.of(2024, 3, 5, 10, 30),
                    Category.HEALTH, Optional.of("仁愛路診所"), Optional.of("帶健保卡")));

    @Autowired
    WebApplicationContext context;

    @Autowired
    FakeArchive archive;

    @Autowired
    StubRestore restore;

    MockMvc mockMvc;

    // 跟 CalendarControllerTest 一樣：每個請求預設已登入、帶著 CSRF token
    @BeforeEach
    void signedIn() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user(SIGNED_IN.toString())).with(csrf()))
                .build();
        archive.asked.clear();
        archive.failure = null;
        restore.calls.clear();
    }

    @Test
    @DisplayName("查看登入者自己的封存：擁有者從登入者來，回每個欄位（標題、時間、分類、地點、備註）")
    void listsSignedInUsersArchivedEvents() throws Exception {
        mockMvc.perform(get("/api/calendar/archive/events").param("from", "2024-03-01").param("to", "2024-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].id").value(DENTIST.id().toString()))
                .andExpect(jsonPath("$.events[0].title").value("看牙醫"))
                .andExpect(jsonPath("$.events[0].start").value("2024-03-05T09:00:00"))
                .andExpect(jsonPath("$.events[0].end").value("2024-03-05T10:30:00"))
                .andExpect(jsonPath("$.events[0].category").value("HEALTH"))
                .andExpect(jsonPath("$.events[0].location").value("仁愛路診所"))
                .andExpect(jsonPath("$.events[0].note").value("帶健保卡"));

        assertEquals(List.of(SIGNED_IN + " 2024-03~2024-04"), archive.asked);
    }

    @Test
    @DisplayName("看一天：from、to 同一天也收")
    void singleDayIsAllowed() throws Exception {
        mockMvc.perform(get("/api/calendar/archive/events").param("from", "2024-03-05").param("to", "2024-03-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].title").value("看牙醫"));
    }

    @Test
    @DisplayName("反例：網址多帶一個 owner 也沒用，查的還是登入者的")
    void ownerParameterIsIgnored() throws Exception {
        mockMvc.perform(get("/api/calendar/archive/events")
                        .param("from", "2024-03-01").param("to", "2024-03-31").param("owner", SOMEONE_ELSE.toString()))
                .andExpect(status().isOk());

        assertEquals(List.of(SIGNED_IN + " 2024-03~2024-04"), archive.asked);
    }

    @Test
    @DisplayName("反例：from 晚於 to、超過 24 個月 → 400，物件儲存一次都沒碰")
    void invalidRangeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/calendar/archive/events").param("from", "2024-03-05").param("to", "2024-03-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get("/api/calendar/archive/events").param("from", "2022-01-31").param("to", "2024-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        assertTrue(archive.asked.isEmpty());
    }

    @Test
    @DisplayName("反例：日期格式不對、缺參數 → 400")
    void malformedDateIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/calendar/archive/events").param("from", "2024-03").param("to", "2024-03-05"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/calendar/archive/events").param("from", "2024-03-01"))
                .andExpect(status().isBadRequest());

        assertTrue(archive.asked.isEmpty());
    }

    @Test
    @DisplayName("物件儲存出事（IllegalStateException）→ 502；回給瀏覽器的話不帶 bucket、key 這些內部細節")
    void storageFailureIsBadGateway() throws Exception {
        archive.failure = new IllegalStateException("歸檔讀取失敗：s3://talkcal-archive/calendar-event/owner=" + SIGNED_IN + "/x.jsonl.gz");

        String body = mockMvc.perform(get("/api/calendar/archive/events").param("from", "2024-03-01").param("to", "2024-03-05"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(ArchiveController.UPSTREAM_FAILURE))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("s3://"), body);
        assertFalse(body.contains(SIGNED_IN.toString()), body);
    }

    @Test
    @DisplayName("還原只放回對話：用登入者和那段月份，回放回幾段；回應裡沒有行程")
    void restoresOnlyConversations() throws Exception {
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-01").param("to", "2024-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations").value(2))
                .andExpect(jsonPath("$.events").doesNotExist());

        MonthRange expected = new MonthRange(YearMonth.of(2024, 1), YearMonth.of(2024, 6));
        assertEquals(List.of("conversations " + SIGNED_IN + " " + expected), restore.calls);
        assertTrue(archive.asked.isEmpty());
    }

    @Test
    @DisplayName("反例：還原的月份 from 晚於 to、超過 24 個月、格式不對、缺參數 → 400，對話沒被動到")
    void invalidRestoreIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-06").param("to", "2024-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2020-01").param("to", "2024-01"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-1-5").param("to", "2024-06"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-01"))
                .andExpect(status().isBadRequest());

        assertTrue(restore.calls.isEmpty());
    }

    /** 假的歸檔：記下被要了誰的、哪幾個月，回一筆固定的行程；需要時丟出設定好的例外。 */
    static final class FakeArchive implements EventArchivePort {

        final List<String> asked = new ArrayList<>();
        IllegalStateException failure;

        @Override
        public void store(OwnerId owner, YearMonth month, List<ScheduledEvent> events) {
            throw new UnsupportedOperationException("查看不該寫歸檔");
        }

        @Override
        public List<ScheduledEvent> load(OwnerId owner, YearMonth firstMonth, YearMonth lastMonth) {
            if (failure != null) {
                throw failure;
            }
            asked.add(owner + " " + firstMonth + "~" + lastMonth);
            return List.of(DENTIST);
        }

        @Override
        public int deleteAll(OwnerId owner) {
            throw new UnsupportedOperationException("查看不該刪歸檔");
        }
    }

    /** 對話的還原：記下呼叫。 */
    static final class StubRestore {

        final List<String> calls = new ArrayList<>();

        int restoreConversations(OwnerId owner, MonthRange months) {
            calls.add("conversations " + owner + " " + months);
            return 2;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

        @Bean
        FakeArchive archive() {
            return new FakeArchive();
        }

        @Bean
        ListArchivedEventsUseCase listArchivedEvents(FakeArchive archive) {
            return new ListArchivedEventsService(archive);
        }

        @Bean
        StubRestore restore() {
            return new StubRestore();
        }

        @Bean
        ArchiveController.RestoreConversations restoreConversations(StubRestore restore) {
            return restore::restoreConversations;
        }
    }
}
