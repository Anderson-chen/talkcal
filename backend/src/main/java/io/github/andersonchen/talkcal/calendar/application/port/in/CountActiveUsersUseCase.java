package io.github.andersonchen.talkcal.calendar.application.port.in;

import java.time.Duration;
import java.util.List;

/**
 * 最近幾段時間內各有幾個人在用（inbound port，#36）：跟助理說過話、或存過行程的人數。
 *
 * 回傳的順序跟 windows 一樣。
 * 失敗時：windows 是 null、空的，或裡面有 null、0、負的期間 → IllegalArgumentException；資料庫出事 → IllegalStateException。
 */
public interface CountActiveUsersUseCase {

    List<Integer> countActiveUsers(List<Duration> windows);
}
