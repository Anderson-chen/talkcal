package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DayPeriod;
import io.github.andersonchen.talkcal.calendar.application.domain.model.FreeSlot;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 某幾天的某個時段裡，哪些時間沒有行程、而且夠長。期間的規則見 DateRange，已經過去的時間不算。
 *
 * 只看 owner 自己的行程：別人的行程不會讓這個人變忙，也不會從空檔的有無被推敲出來。
 *
 * 失敗時：
 * - owner 是 null、期間不合規、最短長度不是正的 → IllegalArgumentException
 * - 資料庫出事 → IllegalStateException
 */
public interface FindFreeSlotsUseCase {

    List<FreeSlot> findFreeSlots(OwnerId owner, LocalDate start, LocalDate endExclusive, DayPeriod period, Duration minimum);
}
