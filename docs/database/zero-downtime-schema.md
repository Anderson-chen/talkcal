# 零停機的 schema 變更（expand/contract）

改資料表的時候服務不用停（#18）。這份文件說明：什麼時候新舊兩版程式會同時連著同一個資料庫、
哪些變更在那時候會壞、安全的做法，以及 2026-10-16 實際演練的一次（行程的起訖時間改存時間點）。

## 什麼時候新舊版本會同時在線

Flyway 在 app 啟動時跑 migration（`spring.flyway.*`），所以 **schema 一定比程式先換新**。
會有「舊程式碰到新 schema」的時候：

| 情況 | 現在有沒有 | 舊版碰到新 schema 多久 |
|---|---|---|
| 退回上一版（[#19](https://github.com/Anderson-chen/talkcal/issues/19)） | 有：程式退得回去，資料庫退不回去（Flyway 社群版沒有 undo，也不該有：退 schema 等於丟資料） | 直到下次往前部署 |
| 滾動更新、藍綠部署（先起新的、健康了再關舊的） | 還沒有：現在是 compose 先關舊的再起新的（見 [postmortem](../postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)）。上 EKS（[#58](https://github.com/Anderson-chen/talkcal/issues/58)）、canary（[#20](https://github.com/Anderson-chen/talkcal/issues/20)）之後每次部署都是 | 整段部署期間；canary 可能好幾個小時 |
| 停機部署也逃不掉的 | 有：migration 跑的那幾秒裡，拿著鎖的 DDL 會讓還在處理的請求卡住 | 鎖拿多久就卡多久 |

所以規則是：**每一支 migration 跑完之後，上一版的程式照樣要能正常讀寫**。現在只有一個 app 也照這個規則寫 ——
等到真的有兩個 app 同時在線才開始，就得回頭檢查每一支已經上線的 migration。

## 做法：expand → migrate → contract

一個「不相容的變更」（改名、改型別、刪欄位）拆成好幾個版本，每一步都只做「新舊兩版都能接受」的事：

| 步驟 | schema | 程式 | 為什麼新舊同時在線不會壞 |
|---|---|---|---|
| 1. expand | 加新欄位（可為 NULL），舊的不動 | 兩邊都寫（雙寫），還讀舊的 | 舊版不知道新欄位，沒碰它；新版寫的舊欄位，舊版讀得到 |
| 2. backfill | 補舊資料的新欄位；補完收緊（NOT NULL、CHECK） | 不變 | 只是把值填上，誰讀誰寫都沒變 |
| 3. 切讀 | 不變 | 讀新的，照樣雙寫 | 舊版還在讀舊欄位，新版照寫，所以舊版讀到的資料一樣完整 |
| 4. 停寫舊的 | 舊欄位改成可為 NULL（或由 trigger 反過來補） | 只寫新的 | 要先確定**沒有任何在線、或會被退回去的版本**還讀舊欄位 |
| 5. contract | 刪舊欄位、舊索引、過渡用的 trigger | 不變 | 已經沒人碰它了 |

一個步驟一個版本，每一步都可以單獨退回上一步。最貴的是「等」：第 4、5 步要等舊版完全下線、
**而且不會再被退回去**，才能往下走。

## 這次的演練：行程起訖時間改存時間點

### 為什麼要改

V2 的 `start_at`、`end_at` 是 `TIMESTAMP`（不帶時區），存的是「台北的牆上時間」。資料庫自己不知道它是台北：
要寄提醒（15:00 開會，該在哪個瞬間通知）、使用者不在台北、要跟別的系統交換時間時，每個讀的人都得自己記得
「這是 Asia/Taipei」。squawk 掃 V2 也會報 `prefer-timestamp-tz`。

domain 不動：`CalendarEvent` 還是 `LocalDateTime`（單一時區的產品決定沒變），換算只在
[JdbcCalendarEventAdapter](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/out/persistence/postgres/JdbcCalendarEventAdapter.java)
做一次，時區用 `calendar.zone`。

### 為什麼不直接 ALTER COLUMN TYPE

```sql
ALTER TABLE calendar_event ALTER COLUMN start_at TYPE TIMESTAMPTZ USING start_at AT TIME ZONE 'Asia/Taipei';
```

兩個問題：

1. **重寫整張表，期間拿著 ACCESS EXCLUSIVE 鎖**：讀寫全部卡住，表越大卡越久。
2. **舊版還在線上時會默默寫錯資料**。舊版照樣送不帶時區的 `LocalDateTime`，PostgreSQL 用連線的時區把它轉成時間點；
   app 在容器裡、JVM 是 UTC，15:00 就變成台北 23:00。實測（PostgreSQL 18.6）：

   ```
   SET TimeZone = 'UTC';
   INSERT INTO naive VALUES ('2026-10-06 15:00'::timestamp);   -- 欄位已經改成 TIMESTAMPTZ
   SELECT start_at AT TIME ZONE 'Asia/Taipei' FROM naive;
   → 2026-10-06 15:00   （改型別之前就在的那筆，USING 換算對了）
   → 2026-10-06 23:00   （改型別之後舊版寫的，錯 8 小時，沒有任何錯誤訊息）
   ```

### 每一步

新欄位叫 `starts_at`、`ends_at`：舊欄位之後會被拿掉，新名字是要長久留下來的那個，所以不加 `_tz` 之類的過渡字尾。

| 步驟 | 版本 | 做了什麼 | 狀態 |
|---|---|---|---|
| expand | 這次 | [V12](../../backend/src/main/resources/db/migration/V12__calendar_event_instant_expand.sql)：加 `starts_at`、`ends_at`（可為 NULL，只改目錄、不掃表）、兩個 `NOT VALID` 的 CHECK、讓「只寫舊欄位的人」也寫到新欄位的 trigger | ✅ |
| backfill | 這次 | [V13](../../backend/src/main/resources/db/migration/V13__calendar_event_instant_backfill.sql)：補既有資料、`VALIDATE`、`SET NOT NULL`（靠驗證過的 CHECK，不掃表） | ✅ |
| 新索引 | 這次 | [V14](../../backend/src/main/resources/db/migration/V14__calendar_event_instant_index.sql)：`CREATE INDEX CONCURRENTLY`，`(owner_id, tstzrange(starts_at, ends_at))` | ✅ |
| 雙寫 | 這次 | adapter 的 INSERT 兩邊都寫 | ✅ |
| 切讀 | 這次 | adapter 的查詢改成 `tstzrange(starts_at, ends_at) && ...`，走 V14 的索引 | ✅ |
| 停寫舊的 | 下一版 | 見[還沒做的](#還沒做的停寫舊欄位與-contract) | ⏳ |
| contract | 再下一版 | 同上 | ⏳ |

雙寫、切讀在 git 裡是兩個 commit（`[feat]` 和 `[refactor]`），但在同一次部署上線。可以這樣做是因為有 trigger，見下一節。

### 為什麼用 trigger

沒有 trigger 的話，標準做法是「先發一版只雙寫的，等舊版全部下線，下一版才 backfill」：
backfill 跟 expand 在同一次部署的話，補完之後舊版又寫進一筆（新欄位是 NULL），補了等於沒補。
而這裡的 migration 是新版 app 啟動時跑的，expand 和 backfill 天生就在同一次部署。

V12 的 trigger 讓「只認得舊欄位的人」寫進來的每一列，新欄位都自動換算好：

| 誰在寫 | 寫了什麼 | trigger 做什麼 |
|---|---|---|
| 舊版 INSERT | 只有 `start_at`、`end_at` | 換算補上 `starts_at`、`ends_at` |
| 舊版 UPDATE | 只改了 `start_at` | `starts_at` 跟著重算，不會留著改之前的值 |
| 新版 INSERT | 兩邊都給 | 新欄位已經有值，不動 |

所以 V12（裝上 trigger）之後，任何版本寫的資料兩邊都有值，V13 的 backfill 只要處理「V12 之前就在的」那些，
同一次部署跑完也不會漏。也因此 V13 能直接把新欄位改成 NOT NULL：舊版寫的列有 trigger 補，不會撞上。

驗證（[JdbcCalendarEventAdapterTest](../../backend/src/integrationTest/java/io/github/andersonchen/talkcal/calendar/adapter/out/persistence/postgres/JdbcCalendarEventAdapterTest.java) 的 `ExpandContract`、
[CalendarEventInstantMigrationTest](../../backend/src/integrationTest/java/io/github/andersonchen/talkcal/calendar/adapter/out/persistence/postgres/CalendarEventInstantMigrationTest.java)）：
「舊版」就是上一版 adapter 實際送的 SQL —— 只寫舊欄位的 INSERT、只改舊欄位的 UPDATE、只讀舊欄位的 SELECT。

| 情境 | 驗證什麼 |
|---|---|
| 舊版寫、新版讀 | 舊版 INSERT 的那筆，新版 `load` 讀得到，時間不變 |
| 舊版改時間 | 新欄位跟著換算 |
| 新版寫、舊版讀（也就是退回上一版） | 舊版的 SELECT 讀得到新版存的行程 |
| 連線是 UTC | 新欄位存的仍然是台北的那個時間點 |
| V11 時就在的資料，升到 V14 | 補上了、而且是對的時間點（凌晨的那筆換成 UTC 是前一天，方向弄反會錯日期） |
| 讀的到底是哪邊 | 刻意讓新舊欄位對不上，`load` 回傳新欄位的值 |

trigger 的代價：每次寫入多一點點成本；邏輯藏在資料庫裡，看程式碼看不到（所以 adapter 的註解指到 V12）；
時區在 trigger 裡寫死 `Asia/Taipei`，跟 `calendar.zone` 要一起改。contract 那一步就拿掉。

### 為什麼不用 pgroll

[pgroll](https://github.com/xataio/pgroll)（Xata）把 expand/contract 自動化：每個版本一個 schema，裡面是 view，
舊版連舊的 view、新版連新的，trigger 和 backfill 它自己產生。做的事跟這裡手寫的一樣，但：

- 要換掉 Flyway（V1～V11 都是 Flyway 管的），migration 改寫成它的 JSON/YAML 格式；
- app 要依版本連到不同的 search_path，部署流程要跟著改；
- 這裡的變更頻率是幾週一次，手寫一次反而看得清楚每一步在做什麼 —— 這份文件就是為了把那些步驟講清楚。

變更一多、或多個服務共用一個資料庫的時候再評估。

### 實測到的兩個坑

**1. `CREATE INDEX CONCURRENTLY` 會讓 Flyway 卡住不動。**
第一次跑 V14 時 migrate 一直沒結束。`pg_stat_activity` 看到的是：

```
 state               | wait_event | query
---------------------+------------+--------------------------------------------
 idle in transaction | ClientRead | SELECT COUNT(*) FROM pg_namespace WHERE ...   ← Flyway 拿著鎖的連線
 active              | virtualxid | CREATE INDEX CONCURRENTLY ...                 ← 等上面那個交易結束
```

Flyway 預設用**交易層級**的 advisory lock（防止兩個 app 同時跑 migration），而 CONCURRENTLY 要等所有進行中的交易結束 ——
包括 Flyway 自己拿著鎖的那一個。互等，部署就卡在 migration。
改成 `spring.flyway.postgresql.transactional-lock=false`（連線層級的鎖）之後，同一支 V14 兩秒內跑完
（[Flyway 文件](https://documentation.red-gate.com/fd/flyway-postgresql-transactional-lock-setting-277579114.html)也是這樣建議）。
整合測試自己呼叫 Flyway 的，收進 `Migrations.flyway(...)` 共用同一個設定：漏掉的那支不會失敗，而是整個整合測試卡到逾時。

**2. `CONCURRENTLY` 的檔案裡不能有 `SET`。**
Flyway 偵測到 CONCURRENTLY 就把整支放到交易外面跑，而 `SET` 被它歸類成交易內的句子，混在一起直接報錯
（`Detected both transactional and non-transactional statements within the same migration`）。所以 V14 沒有 `lock_timeout`，
用 `squawk-ignore` 標明原因：CONCURRENTLY 要的 SHARE UPDATE EXCLUSIVE 鎖排隊時不擋一般讀寫，沒有 timeout 的代價小得多。

另外 squawk 抓到一個我原本寫錯的地方：V13 原本在同一支裡 `ADD CONSTRAINT ... NOT VALID` 緊接著 `VALIDATE`。
同一個交易裡，ADD 拿到的 ACCESS EXCLUSIVE 會一直拿到交易結束，VALIDATE 掃表的時候讀寫全卡住，NOT VALID 白做。
改成在 V12 加、V13 驗。

### 還沒做的：停寫舊欄位與 contract

這兩步要等**上一版（還在讀 `start_at`、`end_at` 的那版）完全下線、而且不會再被退回去**。在那之前做，退回上一版時它讀到的會是 NULL 或舊值。

**下一版：停寫舊欄位。** 程式只寫 `starts_at`、`ends_at`。但這一版（雙寫、讀新的）在部署途中、或被退回時還在讀舊欄位，
所以 trigger 要改成兩個方向都補：

```sql
-- V15（草稿）：trigger 也從新欄位補舊欄位，讓還在讀 start_at 的那一版照樣讀得到
CREATE OR REPLACE FUNCTION calendar_event_sync_instants() RETURNS trigger LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.starts_at := COALESCE(NEW.starts_at, NEW.start_at AT TIME ZONE 'Asia/Taipei');
        NEW.ends_at   := COALESCE(NEW.ends_at,   NEW.end_at   AT TIME ZONE 'Asia/Taipei');
        NEW.start_at  := COALESCE(NEW.start_at,  NEW.starts_at AT TIME ZONE 'Asia/Taipei');
        NEW.end_at    := COALESCE(NEW.end_at,    NEW.ends_at   AT TIME ZONE 'Asia/Taipei');
    ELSE
        -- UPDATE：哪一邊被改了，就用它重算另一邊（兩邊都改了就照新欄位）
        ...
    END IF;
    RETURN NEW;
END
$$;
```

舊欄位的 NOT NULL 可以留著：BEFORE trigger 先跑、NOT NULL 後檢查，trigger 補上值就過得了。

**再下一版：contract。** 確定沒有任何版本讀寫舊欄位之後：

```sql
-- V16（草稿）
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DROP TRIGGER IF EXISTS calendar_event_sync_instants ON calendar_event;
DROP FUNCTION IF EXISTS calendar_event_sync_instants();
-- V15 那一版起，程式就不再讀寫這兩個欄位；那一版之前的版本已經不在退回的範圍內
-- squawk-ignore ban-drop-column
ALTER TABLE calendar_event DROP COLUMN IF EXISTS start_at, DROP COLUMN IF EXISTS end_at;
```

`DROP COLUMN` 只改目錄、不重寫表（空間等之後 VACUUM FULL 或重寫時才回收），V2 的 `CHECK (end_at > start_at)`、
V11 的索引 `calendar_event_owner_period` 都建在這兩個欄位上，會跟著一起被刪掉。
索引要更小心的話，先單獨 `DROP INDEX CONCURRENTLY`（另一支不在交易裡的 migration），刪欄位時就不必在 ACCESS EXCLUSIVE 下刪索引。

contract 之後就**退不回** V15 之前的版本了，這是這個做法本來的代價：每往前一步，能退回的範圍就縮一格。

## 危險的變更和安全的做法

「危險」指的是：新舊版本同時在線時舊版會壞，或者會長時間鎖住整張表。CI 的 squawk（[ci/migrations.sh](../../ci/migrations.sh)）會擋下左欄的寫法。

| 變更 | 為什麼危險 | 安全的做法 | squawk 規則 |
|---|---|---|---|
| 改欄位名稱 | 舊版的 SQL 還在用舊名字，立刻壞 | 加新欄位 → 雙寫 → backfill → 切讀 → 停寫 → 刪舊的（就是這次的流程） | `renaming-column` |
| 改型別 | 重寫整張表（ACCESS EXCLUSIVE）；舊版送的值可能被默默轉錯（見上面的實測） | 同上：新欄位用新型別 | `changing-column-type` |
| 加 NOT NULL、沒有預設值 | 舊版的 INSERT 不認得這個欄位，全部失敗 | 先加成可為 NULL（或給常數預設值，PostgreSQL 11 起不重寫表）→ 程式開始寫 → backfill → `CHECK (...) NOT VALID` → `VALIDATE` → `SET NOT NULL` | `adding-required-field` |
| 對既有欄位 SET NOT NULL | 拿著 ACCESS EXCLUSIVE 掃整張表 | 先有驗證過的 `CHECK (col IS NOT NULL)`，SET NOT NULL 就不掃表（V13 的做法） | `adding-not-nullable-field` |
| 刪欄位、刪表 | 舊版還在讀寫，立刻壞 | 先發一版不碰它的程式，等那之前的版本都不在線、也不會退回，下一版才刪（V8 的註解就寫過這件事） | `ban-drop-column`、`ban-drop-table` |
| 加 CHECK、外鍵 | 拿著鎖掃整張表驗證既有資料 | `ADD CONSTRAINT ... NOT VALID`，**另一支** migration 再 `VALIDATE`（同一支會一路拿著 ADD 的鎖） | `constraint-missing-not-valid`、`adding-foreign-key-constraint` |
| 建索引 | 一般的 CREATE INDEX 整段時間擋寫入 | `CREATE INDEX CONCURRENTLY`，自己一支 migration、`DROP INDEX CONCURRENTLY IF EXISTS` 開頭（失敗會留下 INVALID 的半成品） | `require-concurrent-index-creation` |
| CHECK 允許的值多一個（例如 `Category` 多一類） | 新版寫了新值，舊版讀到 `Category.valueOf` 失敗，含有那筆行程的整段月曆都讀不出來（502） | 先發一版「讀得懂新值」的程式，下一版才放寬 CHECK、開始寫新值 | 抓不到（要懂程式怎麼讀） |
| 任何 ALTER TABLE | 拿不到鎖時會排隊，後面所有讀寫這張表的查詢也排在它後面 —— 等於停機 | `SET LOCAL lock_timeout = '5s'`：等不到就整支 rollback，部署失敗，比卡住所有人好 | `require-lock-timeout` |
| 大量 UPDATE（backfill） | 一句改幾百萬列：長時間佔著列鎖、大量 WAL | 分批（每批幾千列、各自 commit），放在 migration 外面的工作跑；這次的資料量一句就好 | `require-statement-timeout` 只提醒設上限 |
| 改已經跑過的 migration | Flyway 的 checksum 對不上，已經跑過的資料庫一啟動就失敗 | 永遠加一支新的 | `ci/migrations.sh` 第 1 道 |

## Migration 檢查清單

寫一支新的 migration 時，PR 送出前逐條看過：

- [ ] **上一版的程式在這支跑完之後還能正常讀寫嗎？** 照著上一版的 SQL 想一遍：INSERT 少給了哪個欄位、SELECT 讀了哪個欄位、`valueOf` 會不會遇到不認得的值
- [ ] **退回上一版的話呢？** 新版寫進去的資料，上一版讀得懂嗎
- [ ] 沒有改名、改型別、刪欄位、刪表。真的要刪：上一版就已經不讀寫它了嗎？`squawk-ignore` 的註解寫了是從哪一版起
- [ ] 新欄位是可為 NULL 或有常數預設值
- [ ] constraint 是 `NOT VALID`，`VALIDATE` 在另一支
- [ ] 索引是 `CONCURRENTLY`、自己一支、`IF EXISTS`／`IF NOT EXISTS`
- [ ] 開頭有 `SET LOCAL lock_timeout`、`SET LOCAL statement_timeout`（`CONCURRENTLY` 那支除外）
- [ ] 一支只做一件事：DDL 和大量 UPDATE 分開，鎖才不會疊在一起
- [ ] 檔案開頭的註解寫了為什麼（這個 repo 的慣例），以及它是 expand/contract 的哪一步
- [ ] 有整合測試對真的 PostgreSQL 跑過：空的資料庫，以及「停在上一版、塞資料、再升上來」（`Migrations.flyway(...).target(...)`）
- [ ] `ci/migrations.sh` 綠燈

## 參考資料

- [squawk 規則清單](https://squawkhq.com/docs/rules)、[CLI 與設定](https://squawkhq.com/docs/cli)：用的是 2.66.0（2026-09-23）
- [Flyway：postgresql.transactional.lock](https://documentation.red-gate.com/fd/flyway-postgresql-transactional-lock-setting-277579114.html)
- [pgroll（Xata）](https://github.com/xataio/pgroll)、[expand/contract with pgroll](https://xata.io/blog/pgroll-expand-contract)
- PostgreSQL 18 文件：[ALTER TABLE](https://www.postgresql.org/docs/18/sql-altertable.html)（哪個子命令拿哪種鎖、SET NOT NULL 何時可以不掃表）、
  [CREATE INDEX 的 CONCURRENTLY](https://www.postgresql.org/docs/18/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY)、
  [明確的鎖](https://www.postgresql.org/docs/18/explicit-locking.html)
