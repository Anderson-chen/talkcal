# 0006. 行程時間存牆上時間：LocalDateTime + PostgreSQL 不帶時區的 TIMESTAMP

- 狀態：已採用
- 日期：2026-10-05
- 相關：[3b4de8d 加入行事曆模組](https://github.com/Anderson-chen/talkcal/commit/3b4de8d)、epic [#46](https://github.com/Anderson-chen/talkcal/issues/46) 的「時區」

## 背景

使用者說「下午三點」，指的是台北的牆上三點。行事曆是單人、單一時區用的。
但部署環境的時區不一定是台北：容器裡 JVM 的預設時區是 UTC。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| `Instant` + `TIMESTAMPTZ` | 跨時區正確；是「真實時間點」的標準做法 | 每一層都多一次時區換算；`TIMESTAMPTZ` 會依連線的時區設定換算，等於把「不處理時區」排除掉的東西偷偷加回來 |
| `LocalDateTime` + `TIMESTAMP`（不帶時區） | 存的就是使用者說的那個時間，沒有換算 | 不能跨時區；「現在幾點」還是得在某個時區裡問 |

## 決定

**行程的 `start_at`、`end_at` 用 `TIMESTAMP`，對應 domain 的 `LocalDateTime`；「這筆什麼時候寫進來」的 `created_at` 是真實時間點，用 `TIMESTAMPTZ`。**

「現在」的時鐘時區寫明：`calendar.zone=Asia/Taipei`，用 `Clock.system(zone)`，不用 `Clock.systemDefaultZone()`。
不寫明的話，一部署到 Docker，早上九點就變成凌晨一點，「今天晚上」會被算到錯的那天。

JDBC 讀寫也避開 JVM 時區：讀用 `getObject(..., LocalDateTime.class)`，不用 `getTimestamp()`。

## 代價

- 不支援跨時區：使用者到別的時區，行程不會跟著換算。
- 時區設定散在三處要一致：domain 的型別、migration 的欄位型別、組裝根的時鐘。

## 什麼時候重想

- 要支援多時區的使用者或共享行事曆 → 型別換成 `Instant` / `ZonedDateTime`，欄位換 `TIMESTAMPTZ`，每個使用者存自己的時區（[CalendarEvent](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/application/domain/model/CalendarEvent.java) 的註解：「哪天要跨時區，再把型別換掉」）。

## 證據

- [V2__create_calendar_events.sql](../../backend/src/main/resources/db/migration/V2__create_calendar_events.sql)：為什麼不用 `TIMESTAMPTZ`、為什麼 `created_at` 要帶時區
- [CalendarEvent](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/application/domain/model/CalendarEvent.java)：為什麼用 `LocalDateTime` 而不是 `Instant`
- [CalendarConfiguration](../../backend/src/main/java/io/github/andersonchen/talkcal/app/config/CalendarConfiguration.java)：為什麼時鐘的時區要寫明
- [JdbcCalendarEventAdapter](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/out/persistence/postgres/JdbcCalendarEventAdapter.java)：讀寫不經過 JVM 時區
