package eat.calendar.adapter.in.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eat.calendar.application.domain.model.CalendarEvent;
import eat.calendar.application.domain.model.Category;
import eat.calendar.application.domain.model.DayPeriod;
import eat.calendar.application.domain.model.FreeSlot;
import eat.calendar.application.domain.model.ScheduledEvent;
import eat.calendar.application.port.in.FindFreeSlotsUseCase;
import eat.calendar.application.port.in.ListEventsUseCase;
import eat.calendar.application.port.in.ParseEventsUseCase;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 工具本身：模型會怎麼呼叫不在這裡測（那是整合測試的事），這裡只測「呼叫到了之後做對事、回的話看得懂」。
 */
@DisplayName("CalendarTools")
class CalendarToolsTest {

    private static final CalendarEvent DINNER = CalendarEvent.startingAt("跟小明吃飯", LocalDateTime.of(2026, 10, 7, 15, 0))
            .withCategory(Category.SOCIAL).withDetails("拉麵店", null);

    private final List<String> parsed = new ArrayList<>();
    private final List<Object[]> freeSlotCalls = new ArrayList<>();

    private ParseEventsUseCase parse = description -> {
        parsed.add(description);
        return List.of(DINNER);
    };
    private ListEventsUseCase list = (start, end) -> List.of(ScheduledEvent.schedule(DINNER));
    private FindFreeSlotsUseCase free = (start, end, period, minimum) -> {
        freeSlotCalls.add(new Object[] {start, end, period, minimum});
        return List.of(new FreeSlot(LocalDateTime.of(2026, 10, 7, 13, 0), LocalDateTime.of(2026, 10, 7, 15, 0)));
    };

    // 使用者說過的話：預設有講時段（「下午」），讓 propose_events 的「沒講早晚就不提議」不擋到其他測試
    private List<String> userSaid = List.of("明天下午三點跟小明吃飯");

    private CalendarTools tools() {
        return new CalendarTools(parse, list, free, userSaid);
    }

    @Nested
    @DisplayName("propose_events")
    class Propose {

        @Test
        @DisplayName("把那句話交給解析，解析出的行程收進 proposals，回給模型的話列出它們")
        void collectsProposals() {
            CalendarTools tools = tools();

            String said = tools.proposeEvents("明天下午三點跟小明吃飯");

            assertEquals(List.of("明天下午三點跟小明吃飯"), parsed);
            assertEquals(List.of(DINNER), tools.proposals());
            assertTrue(said.contains("2026-10-07（週三）15:00–16:00 跟小明吃飯〔社交〕＠拉麵店"), said);
        }

        @Test
        @DisplayName("同一輪呼叫兩次：兩次的都收著")
        void accumulates() {
            CalendarTools tools = tools();

            tools.proposeEvents("一");
            tools.proposeEvents("二");

            assertEquals(2, tools.proposals().size());
        }

        @Test
        @DisplayName("解析不出行程：不收任何東西，請模型去問使用者")
        void nothingParsed() {
            parse = description -> List.of();
            CalendarTools tools = tools();

            String said = tools.proposeEvents("今天天氣真好");

            assertTrue(tools.proposals().isEmpty());
            assertTrue(said.contains("確認"), said);
        }

        @Test
        @DisplayName("空白的描述（解析丟 IllegalArgumentException）：告訴模型要給一句話，不讓整個請求失敗")
        void blankDescription() {
            parse = description -> {
                throw new IllegalArgumentException("行程描述不可為 null 或空白");
            };

            assertTrue(tools().proposeEvents(" ").contains("一句話"));
        }
    }

    @Nested
    @DisplayName("list_events")
    class ListTool {

        @Test
        @DisplayName("列出行程：日期、星期、起訖、標題、分類、地點")
        void listsEvents() {
            assertEquals("- 2026-10-07（週三）15:00–16:00 跟小明吃飯〔社交〕＠拉麵店", tools().listEvents("2026-10-07", "2026-10-08"));
        }

        @Test
        @DisplayName("沒有行程：明說沒有")
        void empty() {
            list = (start, end) -> List.of();

            assertTrue(tools().listEvents("2026-10-07", "2026-10-08").contains("沒有行程"));
        }

        @Test
        @DisplayName("日期格式錯、期間倒過來：回一句說明讓模型修正，不讓整個請求失敗")
        void badArguments() {
            list = (start, end) -> {
                throw new IllegalArgumentException("期間的結束必須晚於開始");
            };

            assertTrue(tools().listEvents("明天", "後天").contains("yyyy-MM-dd"));
            assertTrue(tools().listEvents("2026-10-08", "2026-10-07").contains("to 要晚於 from"));
        }
    }

    @Nested
    @DisplayName("find_free_slots")
    class FreeTool {

        @Test
        @DisplayName("把參數交給 use case，空檔列出長度")
        void findsSlots() {
            String said = tools().findFreeSlots("2026-10-07", "2026-10-09", DayPeriod.AFTERNOON, 90);

            assertEquals("- 2026-10-07（週三）13:00–15:00（2 小時）", said);
            Object[] call = freeSlotCalls.getFirst();
            assertEquals(LocalDate.of(2026, 10, 7), call[0]);
            assertEquals(LocalDate.of(2026, 10, 9), call[1]);
            assertEquals(DayPeriod.AFTERNOON, call[2]);
            assertEquals(Duration.ofMinutes(90), call[3]);
        }

        @Test
        @DisplayName("模型沒給時段、沒給長度：不限時段、一小時")
        void defaults() {
            tools().findFreeSlots("2026-10-07", "2026-10-08", null, null);

            Object[] call = freeSlotCalls.getFirst();
            assertEquals(DayPeriod.ANYTIME, call[2]);
            assertEquals(Duration.ofHours(1), call[3]);
        }

        @Test
        @DisplayName("沒有空檔：明說沒有")
        void none() {
            free = (start, end, period, minimum) -> List.of();

            assertTrue(tools().findFreeSlots("2026-10-07", "2026-10-08", DayPeriod.EVENING, 60).contains("沒有空檔"));
        }
    }

    @Nested
    @DisplayName("propose_events：沒講早上還是晚上就不提議")
    class MorningOrEvening {

        private CalendarEvent at(int hour) {
            return CalendarEvent.startingAt("吃飯", LocalDateTime.of(2026, 10, 7, hour, 0));
        }

        @Test
        @DisplayName("使用者回報：「明天七點吃飯」模型補成晚上 19:00 → 不提議，請模型先問")
        void ambiguousHourWithoutPeriodIsRefused() {
            userSaid = List.of("明天七點吃飯");
            for (int hour : new int[] {7, 11, 19, 23}) {
                parse = description -> List.of(at(hour));
                CalendarTools tools = tools();

                String said = tools.proposeEvents("明天晚上七點吃飯");

                assertTrue(tools.proposals().isEmpty(), hour + " 點不該提議");
                assertTrue(said.contains("早上還是晚上"), said);
            }
        }

        @Test
        @DisplayName("使用者講過時段（晚上、晚餐、晨跑、宵夜、19點、7pm），或在之後回答了「晚上」→ 照常提議")
        void periodSaidSomewhereInTheConversation() {
            parse = description -> List.of(at(19));
            for (List<String> said : List.of(
                    List.of("明天七點吃晚餐"), List.of("明天七點晨跑"), List.of("明天十一點吃宵夜"),
                    List.of("明天19點開會"), List.of("tomorrow 7pm dinner"), List.of("明天七點吃飯", "晚上"))) {
                userSaid = said;
                CalendarTools tools = tools();

                tools.proposeEvents("明天晚上七點吃飯");

                assertEquals(1, tools.proposals().size(), said.toString());
            }
        }

        @Test
        @DisplayName("不會被誤會的時段（1～6 點當下午、12～18 點）：沒講時段也照常提議")
        void unambiguousHours() {
            userSaid = List.of("明天三點開會");
            for (int hour : new int[] {12, 15, 18}) {
                parse = description -> List.of(at(hour));
                CalendarTools tools = tools();

                tools.proposeEvents("明天下午三點開會");

                assertEquals(1, tools.proposals().size(), hour + " 點");
            }
        }
    }
}

