package io.github.andersonchen.talkcal.calendar.application.domain.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 沒登入的訪客點固定問題時，助理預先寫好的回答。不呼叫模型，訪客看幾次都沒有成本。
 *
 * 問題要跟 web/src/calendar/useAssistant.ts 的 SUGGESTIONS 一字不差：前端送的是按鈕上的文字，對不上就是 400。
 * 回答裡的日期都從 today 算，「明天」永遠是看的那天的隔天；查行程、找空檔的回答用的是寫死的示範行程，不是誰的行事曆。
 */
public final class GuestDemo {

    public static final String ADD_MEETING = "明天下午3點和 Amy 開會";
    public static final String TOMORROW = "明天有什麼行程？";
    public static final String FREE_AFTERNOONS = "這週哪天下午有空？";

    public static final List<String> QUESTIONS = List.of(ADD_MEETING, TOMORROW, FREE_AFTERNOONS);

    private static final String SAMPLE_NOTE = "（這是示範用的行程。登入後，助理查的是你自己的行事曆。）";

    private GuestDemo() {
    }

    /** proposals 是提議卡片，只給畫面看：訪客按確認時前端帶去登入，不會存 */
    public record Answer(String reply, List<CalendarEvent> proposals) {

        public Answer {
            proposals = List.copyOf(proposals);
        }
    }

    public static boolean isQuestion(String question) {
        return QUESTIONS.contains(question);
    }

    /** 不是固定的問題回 empty */
    public static Optional<Answer> answer(String question, LocalDate today) {
        if (question == null || today == null) {
            return Optional.empty();
        }
        return switch (question) {
            case ADD_MEETING -> Optional.of(addMeeting(today));
            case TOMORROW -> Optional.of(tomorrow(today));
            case FREE_AFTERNOONS -> Optional.of(freeAfternoons(today));
            default -> Optional.empty();
        };
    }

    private static Answer addMeeting(LocalDate today) {
        CalendarEvent meeting = CalendarEvent.startingAt("和 Amy 開會", today.plusDays(1).atTime(15, 0))
                .withCategory(Category.WORK);
        return new Answer("幫你整理成卡片了，確認後就會加進行事曆。", List.of(meeting));
    }

    private static Answer tomorrow(LocalDate today) {
        LocalDate day = today.plusDays(1);
        String reply = "明天 " + label(day) + " 有 2 個行程：\n"
                + "・09:30–10:00　團隊晨會\n"
                + "・12:00–13:00　和 Amy 吃午餐\n"
                + "下午都是空的。\n" + SAMPLE_NOTE;
        return new Answer(reply, List.of());
    }

    // 「這週」是今天到週日。今天就是週日時只剩今天，就只講今天
    private static Answer freeAfternoons(LocalDate today) {
        List<LocalDate> rest = new ArrayList<>();
        for (LocalDate day = today.plusDays(1); day.getDayOfWeek() != DayOfWeek.MONDAY; day = day.plusDays(1)) {
            rest.add(day);
        }
        StringBuilder reply = new StringBuilder("這週下午有空的時段：\n");
        if (rest.isEmpty()) {
            reply.append(slot(today, LocalTime.of(15, 0)));
        } else {
            reply.append(slot(rest.getFirst(), LocalTime.of(14, 0)));
            if (rest.size() > 1) {
                reply.append(slot(rest.getLast(), LocalTime.of(13, 0)));
            }
        }
        return new Answer(reply.append(SAMPLE_NOTE).toString(), List.of());
    }

    private static String slot(LocalDate day, LocalTime from) {
        return "・" + label(day) + "　" + from + "–18:00\n";
    }

    // 例如 11/19（週四）
    private static String label(LocalDate day) {
        return day.getMonthValue() + "/" + day.getDayOfMonth()
                + "（" + day.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.TAIWAN) + "）";
    }
}
