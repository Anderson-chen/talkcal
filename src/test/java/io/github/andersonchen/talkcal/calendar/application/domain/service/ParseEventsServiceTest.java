package io.github.andersonchen.talkcal.calendar.application.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.andersonchen.talkcal.calendar.application.domain.model.CalendarEvent;
import io.github.andersonchen.talkcal.calendar.application.domain.model.EventDescription;
import io.github.andersonchen.talkcal.calendar.application.port.out.ExtractEventsPort;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ParseEventsService")
class ParseEventsServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    // 台北 2026-10-05 09:30（UTC 01:30）。固定下來，「現在」在每次測試都一樣
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-05T01:30:00Z"), TAIPEI);

    private static final CalendarEvent DINNER =
            CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 6, 15, 0));
    private static final CalendarEvent DENTIST =
            CalendarEvent.startingAt("看牙醫", LocalDateTime.of(2026, 10, 7, 9, 0));

    // 假模型：記下收到的描述和時間，回傳事先給好的行程
    private static final class RecordingPort implements ExtractEventsPort {
        final List<CalendarEvent> result;
        EventDescription description;
        LocalDateTime now;

        RecordingPort(List<CalendarEvent> result) {
            this.result = result;
        }

        @Override
        public List<CalendarEvent> extractEvents(EventDescription description, LocalDateTime now) {
            this.description = description;
            this.now = now;
            return result;
        }
    }

    @Test
    @DisplayName("把描述交給 port，原樣回傳拆好的多筆行程")
    void returnsAllExtractedEvents() {
        RecordingPort port = new RecordingPort(List.of(DINNER, DENTIST));

        List<CalendarEvent> events = new ParseEventsService(port, FIXED).parseEvents("明天三點跟小明吃飯，後天九點看牙醫");

        assertEquals(List.of(DINNER, DENTIST), events);
        assertEquals("明天三點跟小明吃飯，後天九點看牙醫", port.description.text());
    }

    @Test
    @DisplayName("「現在」取自注入的時鐘，而且是那個時區的牆上時間")
    void passesNowFromClockInItsZone() {
        RecordingPort port = new RecordingPort(List.of());

        new ParseEventsService(port, FIXED).parseEvents("明天三點跟小明吃飯");

        // UTC 01:30 在台北是 09:30：時區由時鐘決定，service 自己不知道是台北
        assertEquals(LocalDateTime.of(2026, 10, 5, 9, 30), port.now);
    }

    @Test
    @DisplayName("沒看出任何行程：回傳空的，不算失敗")
    void noEventsIsNotAFailure() {
        List<CalendarEvent> events = new ParseEventsService(new RecordingPort(List.of()), FIXED).parseEvents("今天天氣真好");

        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("描述空白時拒絕，而且不會呼叫模型")
    void rejectsBlankDescriptionBeforeCallingPort() {
        ExtractEventsPort mustNotBeCalled = (description, now) -> fail("空白描述不該呼叫模型");

        assertThrows(IllegalArgumentException.class, () -> new ParseEventsService(mustNotBeCalled, FIXED).parseEvents(" "));
    }

    @Test
    @DisplayName("回傳的清單改不動，即使 adapter 給的是可變的")
    void returnsUnmodifiableList() {
        List<CalendarEvent> mutable = new ArrayList<>(List.of(DINNER));

        List<CalendarEvent> events = new ParseEventsService(new RecordingPort(mutable), FIXED).parseEvents("明天三點跟小明吃飯");

        assertThrows(UnsupportedOperationException.class, () -> events.add(DENTIST));
    }

    @Test
    @DisplayName("模型出事時，port 的例外原樣往外丟")
    void propagatesPortFailure() {
        IllegalStateException failure = new IllegalStateException("llama.cpp 連不上");
        ExtractEventsPort broken = (description, now) -> {
            throw failure;
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ParseEventsService(broken, FIXED).parseEvents("明天三點跟小明吃飯"));
        assertSame(failure, thrown);
    }
}
