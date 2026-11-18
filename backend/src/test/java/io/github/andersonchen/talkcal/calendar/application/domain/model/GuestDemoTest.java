package io.github.andersonchen.talkcal.calendar.application.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GuestDemo：訪客的示範回答")
class GuestDemoTest {

    // 週三
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 11, 18);

    @Test
    @DisplayName("新增行程：卡片是看的那天的隔天下午 3 點；反例：換一天看，卡片跟著換成那天的隔天")
    void meetingIsTomorrowAtThree() {
        CalendarEvent card = GuestDemo.answer(GuestDemo.ADD_MEETING, WEDNESDAY).orElseThrow().proposals().getFirst();
        assertEquals(LocalDateTime.of(2026, 11, 19, 15, 0), card.start());
        assertEquals(LocalDateTime.of(2026, 11, 19, 16, 0), card.end());
        assertEquals(Category.WORK, card.category());

        CalendarEvent later = GuestDemo.answer(GuestDemo.ADD_MEETING, LocalDate.of(2026, 12, 31)).orElseThrow().proposals().getFirst();
        assertEquals(LocalDateTime.of(2027, 1, 1, 15, 0), later.start());
    }

    @Test
    @DisplayName("明天有什麼行程：回答寫出明天的日期和星期，沒有卡片")
    void tomorrowNamesTheDate() {
        GuestDemo.Answer answer = GuestDemo.answer(GuestDemo.TOMORROW, WEDNESDAY).orElseThrow();
        assertTrue(answer.reply().contains("11/19（週四）"), answer.reply());
        assertTrue(answer.proposals().isEmpty());
    }

    @Test
    @DisplayName("這週哪天下午有空：列出明天和週日；反例：明天就是週日只講一次，今天是週日時只講今天，不會講到下週")
    void freeAfternoonsStayInThisWeek() {
        String midweek = GuestDemo.answer(GuestDemo.FREE_AFTERNOONS, WEDNESDAY).orElseThrow().reply();
        assertTrue(midweek.contains("11/19（週四）"), midweek);
        assertTrue(midweek.contains("11/22（週日）"), midweek);

        String saturday = GuestDemo.answer(GuestDemo.FREE_AFTERNOONS, LocalDate.of(2026, 11, 21)).orElseThrow().reply();
        assertEquals(1, saturday.split("11/22（週日）", -1).length - 1, saturday);

        String sunday = GuestDemo.answer(GuestDemo.FREE_AFTERNOONS, LocalDate.of(2026, 11, 22)).orElseThrow().reply();
        assertTrue(sunday.contains("11/22（週日）"), sunday);
        assertTrue(!sunday.contains("11/23"), sunday);
    }

    @Test
    @DisplayName("反例：不是固定的問題、差一個字、null 都沒有回答")
    void onlyFixedQuestions() {
        assertEquals(Optional.empty(), GuestDemo.answer("幫我刪掉所有行程", WEDNESDAY));
        assertEquals(Optional.empty(), GuestDemo.answer("明天有什麼行程?", WEDNESDAY));
        assertEquals(Optional.empty(), GuestDemo.answer(null, WEDNESDAY));
    }
}
