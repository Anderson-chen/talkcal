package io.github.andersonchen.talkcal.calendar.application.port.in;

import java.time.Duration;

/**
 * 最近一段時間內有幾個人在用（inbound port，#36）：跟助理說過話、或存過行程的人數。
 *
 * 失敗時：window 是 null、0 或負的 → IllegalArgumentException；資料庫出事 → IllegalStateException。
 */
public interface CountActiveUsersUseCase {

    int countActiveUsers(Duration window);
}
