package io.github.andersonchen.talkcal.calendar.application.port.in;

import io.github.andersonchen.talkcal.calendar.application.domain.model.DayPeriod;
import io.github.andersonchen.talkcal.calendar.application.domain.model.FreeSlot;
import io.github.andersonchen.talkcal.calendar.application.domain.model.OwnerId;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * 在空檔裡排時段（inbound port，#261）：某幾天的某個時段裡，每天挑一段 length 長的時間。
 *
 * 只排、不存：回傳的是建議的時段，要不要加進行事曆由使用者確認。
 * 只看 owner 自己的行程，跟 FindFreeSlotsUseCase 一樣（#104）。
 *
 * 失敗時：
 * - owner 是 null、期間不合規或超過 MAX_DAYS 天、長度不是正的 → IllegalArgumentException
 * - 資料庫出事 → IllegalStateException
 */
public interface PlanSessionsUseCase {

    /** 一次最多排幾天：「這學期每天都幫我排」這種會一次出上百張卡片。 */
    int MAX_DAYS = 14;

    List<FreeSlot> planSessions(OwnerId owner, LocalDate start, LocalDate endExclusive, DayPeriod period, Duration length);
}
