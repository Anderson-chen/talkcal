# 0004. 資料庫存取用 JdbcClient 手寫 SQL，不用 JPA

- 狀態：已採用
- 日期：2026-10-02
- 相關：[7b2824c 對話紀錄存 PostgreSQL](https://github.com/Anderson-chen/talkcal/commit/7b2824c)、[0005](0005-hexagonal-with-archunit.md)

## 背景

第一次要存東西（多輪對話的紀錄）時，domain 已經是純 Java 的 record 和類別，ArchUnit 守著 core 不准依賴框架（[0005](0005-hexagonal-with-archunit.md)）。
資料表很少（現在行程、助理記憶、帳號幾張），schema 由 Flyway 管。

## 選項

| 選項 | 好處 | 代價 |
|---|---|---|
| JPA / Hibernate | 不用寫 SQL；關聯、髒檢查、樂觀鎖有現成的 | domain 物件要加 `@Entity` 註解或另外寫一層 entity；實際送出的 SQL 要開 log 才看得到 |
| `JdbcClient` 手寫 SQL | SQL 一眼看得懂；domain 不必為 ORM 改樣子；PostgreSQL 特有的東西（`tsrange` + GiST、`ON CONFLICT`、JSONB）直接寫 | 讀寫的對應要自己寫；表多了以後重複的程式會變多 |

## 決定

**adapter 用 Spring 的 `JdbcClient` 手寫 SQL；交易用 `TransactionTemplate`。**

`TransactionTemplate` 而不是 `@Transactional`：adapter 是 `final` 類別，CGLIB 代理包不了。
一個 adapter 實作同一張表的讀、存、刪幾個 port，「這張表長什麼樣」的知識只在一個地方。

## 代價

- 欄位對應、型別轉換自己寫，例如 `TIMESTAMP` 要用 `getObject(..., LocalDateTime.class)` 讀，不能用會經過 JVM 時區的 `getTimestamp()`（見 [0006](0006-wall-clock-timestamp-without-time-zone.md)）。
- 樂觀鎖、批次要自己寫（例如當時對話用 `UPDATE ... WHERE version = ?`）。

## 什麼時候重想

- 表和關聯多到手寫對應變成主要工作量。

## 證據

- [JdbcCalendarEventAdapter](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/out/persistence/postgres/JdbcCalendarEventAdapter.java)：類別註解（JdbcClient、TransactionTemplate、一個類別實作三個 port）
- [CalendarConfiguration](../../backend/src/main/java/io/github/andersonchen/talkcal/app/config/CalendarConfiguration.java)：組裝根把 `JdbcClient` 和 `TransactionTemplate` 交給 adapter
- [README.md 的技術選型](../../README.md#技術選型)
- [db/migration](../../backend/src/main/resources/db/migration)：每支 SQL 開頭寫了理由
