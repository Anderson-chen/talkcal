package io.github.andersonchen.talkcal.calendar.adapter.in.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.andersonchen.talkcal.calendar.application.domain.model.MonthRange;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;
import io.github.andersonchen.talkcal.calendar.application.port.in.RestoreEventsUseCase;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

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
 * ArchiveController 走一遍真的 Spring MVC（#54）：月份怎麼綁、範圍不合規回 400、儲存出事回 502、擁有者只從登入者來。
 * 行程和對話的還原由手寫的假物件扮演，記下被叫了幾次、拿到什麼參數。
 */
@WebMvcTest(ArchiveController.class)
@Import(ArchiveControllerTest.StubConfiguration.class)
@DisplayName("ArchiveController")
class ArchiveControllerTest {

    private static final OwnerId SIGNED_IN = OwnerId.of("3f1c8a2e-0000-4000-8000-000000000001");

    @Autowired
    WebApplicationContext context;

    @Autowired
    StubRestore restore;

    MockMvc mockMvc;

    // 跟 CalendarControllerTest 一樣：每個請求預設已登入、帶著 CSRF token；認證本身在 SecurityTest 測
    @BeforeEach
    void signedIn() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user(SIGNED_IN.toString())).with(csrf()))
                .build();
        restore.calls.clear();
        restore.failure = null;
    }

    @Test
    @DisplayName("還原登入者自己的：行程、對話都用登入者和同一段月份，回放回幾筆、幾段")
    void restoresForSignedInUser() throws Exception {
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-01").param("to", "2024-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").value(3))
                .andExpect(jsonPath("$.conversations").value(2));

        MonthRange expected = new MonthRange(YearMonth.of(2024, 1), YearMonth.of(2024, 6));
        assertEquals(List.of("events " + SIGNED_IN + " " + expected, "conversations " + SIGNED_IN + " " + expected),
                restore.calls);
    }

    @Test
    @DisplayName("反例：from 晚於 to、超過 24 個月 → 400，行程和對話都沒被動到")
    void invalidRangeIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-06").param("to", "2024-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2020-01").param("to", "2024-01"))
                .andExpect(status().isBadRequest());

        assertTrue(restore.calls.isEmpty());
    }

    @Test
    @DisplayName("反例：月份格式不對、缺參數 → 400")
    void malformedMonthIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-1-5").param("to", "2024-06"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-01"))
                .andExpect(status().isBadRequest());

        assertTrue(restore.calls.isEmpty());
    }

    @Test
    @DisplayName("物件儲存或資料庫出事（IllegalStateException）→ 502")
    void storageFailureIsBadGateway() throws Exception {
        restore.failure = new IllegalStateException("歸檔讀取失敗");

        mockMvc.perform(post("/api/calendar/archive/restore").param("from", "2024-01").param("to", "2024-01"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("歸檔讀取失敗"));
    }

    /** 行程和對話的還原都由它演：記下呼叫，需要時丟出設定好的例外。 */
    static final class StubRestore implements RestoreEventsUseCase {

        final List<String> calls = new ArrayList<>();
        IllegalStateException failure;

        @Override
        public int restore(OwnerId owner, MonthRange months) {
            if (failure != null) {
                throw failure;
            }
            calls.add("events " + owner + " " + months);
            return 3;
        }

        int restoreConversations(OwnerId owner, MonthRange months) {
            calls.add("conversations " + owner + " " + months);
            return 2;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubConfiguration {

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
