package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.GuestDemo;

/**
 * 沒登入的訪客點固定問題，看助理預先寫好的回答（GuestDemo）。每個來源每天有次數上限，看完再決定要不要註冊。
 */
public interface ShowGuestDemoUseCase {

    /** 每個來源每天能看幾次 */
    int DAILY_LIMIT = 3;

    /**
     * client 是區分訪客的依據（目前是 IP），由呼叫端決定怎麼取。
     * 失敗時：question 不是固定的問題、client 空白 → IllegalArgumentException，不算一次；
     * 今天已經看滿 DAILY_LIMIT 次 → GuestDemoLimitException。
     */
    Shown show(String client, String question);

    /** remaining：今天還能再看幾次 */
    record Shown(GuestDemo.Answer answer, int remaining) {
    }
}
