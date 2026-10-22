# 零停機的 schema 變更（expand/contract）

改資料表的時候服務不用停（#18）。這份文件是**通用的做法**：什麼時候新舊兩版程式會同時連著同一個資料庫、
哪些變更在那時候會壞、要拆成哪幾次發布、每一次要等什麼前提。真的碰到需求時照著走。

這裡沒有為了示範去改產品的資料表。做法是用一張演練表實測證明的，結果在[演練紀錄](#演練紀錄)。
日常擋問題的是 CI 的 squawk（[ci/migrations.sh](../../ci/migrations.sh)）：每個新增的 migration 都自動掃，演練則是一次性的證明。

## 什麼時候新舊版本會同時在線

Flyway 在 app 啟動時跑 migration（`spring.flyway.*`），所以 **schema 一定比程式先換新**。
會有「舊程式碰到新 schema」的時候：

| 情況 | 現在有沒有 | 舊版碰到新 schema 多久 |
|---|---|---|
| 退回上一版（[#19](https://github.com/Anderson-chen/talkcal/issues/19)） | 有：程式退得回去，資料庫退不回去（Flyway 社群版沒有 undo，也不該有：退 schema 等於丟資料） | 直到下次往前部署 |
| 滾動更新、藍綠部署（先起新的、健康了再關舊的） | 上 EKS（[#58](https://github.com/Anderson-chen/talkcal/issues/58)）、canary（[#20](https://github.com/Anderson-chen/talkcal/issues/20)）之後每次部署都是 | 整段部署期間；canary 可能好幾個小時 |
| 停機部署也逃不掉的 | 有：migration 跑的那幾秒裡，拿著鎖的 DDL 會讓還在處理的請求卡住 | 鎖拿多久就卡多久 |

所以規則是：**每一次發布，「這次的版本」和「上一版」都要能用這次的 schema 正常讀寫**。
上一版會在部署途中還在跑，出事時也會被退回去。現在只有一個 app 也照這個規則寫：
等到真的有兩個 app 同時在線才開始，就得回頭檢查每一支已經上線的 migration。

## 做法：拆成幾次發布，靠發布順序，不靠資料庫

一個不相容的變更（改名、改型別、刪欄位）拆成好幾次發布。以「欄位 `old` 改成 `new`」為例：

| 發布 | migration（跟這版程式一起上線） | 程式 | 同時在線 | 這次發布的前提 |
|---|---|---|---|---|
| R1 | — | v1：只認得 `old` | v1 | — |
| R2 expand | 加 `new`（可為 NULL）；`old` 放寬成可為 NULL | v2：**雙寫**，讀 `old` | v1、v2 | 無：只加東西，v1 照常 |
| R3 backfill、切讀 | `CHECK (new IS NOT NULL) NOT VALID` → 補舊資料 → `VALIDATE` + `SET NOT NULL` → 新索引 `CONCURRENTLY` | v3：雙寫，**讀 `new`** | v2、v3 | **v1 不在了**：沒人再寫 NULL 的 `new`，補完不會再冒出來 |
| R4 停寫舊的 | — | v4：只寫、只讀 `new` | v3、v4 | **v2 不在了**：沒人讀 `old` |
| R5 contract | 刪 `old` | 不變 | v4 | **v3 不在了**：沒人寫 `old` |

「不在了」的意思是：不在線上，**也不在能退回的範圍內**。這個 repo 退回是退到上一個 release（[#19](https://github.com/Anderson-chen/talkcal/issues/19)），
所以實際上就是「隔一次發布」：R3 的前提靠 R2 已經完整上線、而且 R3 出事只會退回 R2 來保證。

最貴的是等。每往前一步，能退回的範圍就縮一格；contract 之後就退不回 v3 了，這是這個做法本來的代價。

### 雙寫寫在哪裡

寫在 persistence adapter（這個 repo 是 `Jdbc*Adapter`），也就是本來就負責「domain 物件 ↔ 欄位」的那一層：

- INSERT、UPDATE 兩個欄位都給同一個值；SELECT 讀哪一邊，由這一版決定。
- 換成 JPA 或 MyBatis 也是同一層的事：entity 兩個欄位都對應、mapper 兩邊都寫。邏輯跟著 adapter 走，不會留在資料庫裡沒人看見。
- 它是**過渡用的程式碼**：R4 拿掉寫 `old` 的那半，R5 之後完全消失。下一次再改欄位，是另一段新的過渡程式碼，不會一直疊在同一個地方。

### 為什麼不用 trigger

另一種常見做法，是在 R2 裝一個 trigger，讓「只寫 `old` 的 v1」寫進來的列也自動補上 `new`。
這樣 backfill 可以跟 expand 在同一次發布，少等一次。這裡刻意不這樣做：

| | trigger | 發布順序＋app 雙寫 |
|---|---|---|
| 換算規則在哪 | 資料庫的 plpgsql **和** adapter 各一份，兩邊要一起改 | 只在 adapter |
| 換 ORM | trigger 還在，ORM 看不到它在寫欄位 | 跟著 adapter 改寫 |
| 下一次變更 | 再改這個 trigger 或多一支，contract 時要記得拆 | 新的一段過渡程式碼，用完就刪 |
| 測試 | 要另外測「資料庫幫你寫了什麼」 | 測 adapter 的 SQL 就好 |
| 代價 | — | 多等一次發布 |

多等一次發布換來的是：資料庫只放資料和約束，不放業務規則。業界的做法也是拆發布：
GitLab 把 migration 分成部署前跑的和[部署後才跑的](https://docs.gitlab.com/development/database/post_deployment_migrations/)，
大量 backfill 另外用[分批的背景 migration](https://docs.gitlab.com/development/database/batched_background_migrations/)；
Rails 的 [strong_migrations](https://github.com/ankane/strong_migrations) 對改名、刪欄位也是建議分幾次部署。

### backfill 以舊欄位為準，不只補 NULL

R2 期間 v1 還在線上，它改資料只改 `old`；v2 雙寫過的那一列，`new` 就停在舊值。
所以 R3 的 backfill 條件是 `new IS DISTINCT FROM old`，以 `old` 為準整個蓋過去（那時寫 `old` 的 v1、v2 都在寫，`old` 才是對的）。
只補 `IS NULL` 的話，被 v1 改過的那些會留著舊值，v3 一切讀就讀錯。

### 一支 migration 只做一件事

Flyway 一支 migration 一個交易，交易裡拿到的鎖會一直拿到交易結束。所以 R3 拆成好幾支：

- `ADD CONSTRAINT ... NOT VALID` 拿 ACCESS EXCLUSIVE（一瞬間），跟掃表的 UPDATE、`VALIDATE` 放同一支，整段讀寫都卡住。
- `VALIDATE` 只拿 SHARE UPDATE EXCLUSIVE（不擋讀寫）；驗證過的 `IS NOT NULL` CHECK 讓 `SET NOT NULL` 不必再掃表（PostgreSQL 12 起）。
- `CREATE INDEX CONCURRENTLY` 不能在交易裡跑，一定要自己一支。

### 為什麼不用 pgroll

[pgroll](https://github.com/xataio/pgroll)（Xata）把 expand/contract 自動化：每個版本一個 schema，裡面是 view，
舊版連舊的 view、新版連新的，雙寫和 backfill 它用 trigger 自己產生。但：

- 要換掉 Flyway，migration 改寫成它的 JSON/YAML 格式；
- app 要依版本連到不同的 search_path，部署流程要跟著改；
- 這裡的變更頻率是幾週一次，手動拆發布反而看得清楚每一步在做什麼。

變更一多、或多個服務共用一個資料庫的時候再評估。

## 演練紀錄

2026-10-22 實測（PostgreSQL 18.6 容器）。測試和演練用的 migration **沒有留在 CI**：它們測的是假的 adapter 和一張演練表，
產品之後改欄位時順序做錯，它們也不會紅，留著只是每次多跑半分鐘。程式碼封存在 `archive/18-zero-downtime-drill` 分支的
[`d0c7aeb`](https://github.com/Anderson-chen/talkcal/tree/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a)（下面的連結都指向這個 commit，分支刪了也看得到）。
產品第一次真的要做不相容的變更、或第一次要 `CREATE INDEX CONCURRENTLY` 時，照這裡的寫法對真的 adapter 寫測試
（[#199](https://github.com/Anderson-chen/talkcal/issues/199)）。

### 欄位改名，分五次發布

[ExpandContractDrillTest](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/java/io/github/andersonchen/talkcal/schema/ExpandContractDrillTest.java)
把 `drill_contact.phone` 改名成 `phone_number`，照上表走 R1～R5。每一版「程式」就是它的 adapter 送的 SQL
（[ContactApp](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/java/io/github/andersonchen/talkcal/schema/ContactApp.java)）。

| migration | 發布 | 做什麼 |
|---|---|---|
| [V1](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/resources/db/drill/V1__create_contact.sql) | R1 | 起點：`phone NOT NULL` |
| [V2](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/resources/db/drill/V2__expand_phone_number.sql) | R2 | 加 `phone_number`、`phone` 放寬成可為 NULL |
| [V3](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/resources/db/drill/V3__phone_number_present_check.sql) | R3 | `CHECK (phone_number IS NOT NULL) NOT VALID` |
| [V4](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/resources/db/drill/V4__backfill_phone_number.sql) | R3 | 以 `phone` 為準補 `phone_number` |
| [V5](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/resources/db/drill/V5__phone_number_not_null.sql) | R3 | `VALIDATE`、`SET NOT NULL`、拿掉 CHECK |
| [V6](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/resources/db/drill/V6__phone_number_index.sql) | R3 | `CREATE INDEX CONCURRENTLY` |
| [V7](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/resources/db/drill/V7__contract_phone.sql) | R5 | 刪 `phone` |

正向：一個資料庫照順序跑完五次發布。每次 migration 一跑完，在線的兩版先讀一遍之前所有發布寫的、改的資料；
接著各自新增、輪流修改既有的每一筆，再讀一遍。每一筆、每一版讀到的都要是最後寫的那個值。

反例（每個都拿掉一個前提，看到在線的某一版壞掉）：

| 拿掉的前提 | 結果 |
|---|---|
| R3 時 v1 還在線上（expand 跟 backfill 同一次發布） | v1 新增被 V3 的 CHECK 擋下 |
| backfill 之前就切讀（R2 的 schema 上跑 v3） | v1 寫的那筆，v3 讀到 NULL |
| backfill 只補 NULL | R2 時 v1 改過的那筆，v3 讀到改之前的值 |
| R4 時 v2 還在線上 | v4 新增的那筆，v2 讀到 NULL |
| R5 時 v3 還在線上 | 欄位不存在，v3 新增、修改都失敗 |

正向測試本身也驗過抓得到錯：把 V4 改成只補 NULL，它在「R3 時 V3 讀到的電話不對」失敗；
把 V3 的 CHECK 提前放進 V2，它在 R2 v1 新增時失敗。

不另外測 schema 長什麼樣（欄位是不是 NOT NULL、索引在不在）：那是 PostgreSQL 照 DDL 做的事，
migrate 沒丟例外就代表都執行了。測的是程式讀到什麼。

### migration 跑的當下，請求不被卡住

上一節是「新舊版本都能用」；這節是「不停」：migration 跟同時進來的請求搶同一張表的鎖時會怎樣。
[MigrationLockTest](https://github.com/Anderson-chen/talkcal/blob/d0c7aebfcd3fc765f35bae64e328aaa5bd7ddb4a/backend/src/integrationTest/java/io/github/andersonchen/talkcal/schema/MigrationLockTest.java)
用真的交易模擬「還沒結束的請求」（開了、碰過表、還沒 commit），每個設計都配一個拿掉它的反例：

| 設計 | 測什麼 | 反例：拿掉這個設計 |
|---|---|---|
| `SET LOCAL lock_timeout = '5s'`（演練 V2） | 有讀到一半的請求時，V2 約 5 秒就放棄、整支 rollback；排在它後面的讀取最多多等那幾秒；請求結束後重跑就過 | 同一句 ALTER TABLE 不設 timeout：7 秒後後面的讀取還卡著，那個請求不結束就一直卡 |
| `CREATE INDEX CONCURRENTLY`（演練 V6） | V6 在等寫到一半的請求時，程式照樣存得進資料（每筆 3 秒內）；請求一結束 V6 就跑完 | 不帶 CONCURRENTLY：建好之前寫入一直卡著 |
| `spring.flyway.postgresql.transactional-lock=false` | 沒有任何別的請求時，V6 幾秒內跑完 | Flyway 預設的交易層級鎖：一樣沒有別的請求，V6 等的是 Flyway 自己，永遠跑不完 |

「排在 ALTER TABLE 後面的讀取也會卡住」是這幾個設計要防的事：ALTER TABLE 在排 ACCESS EXCLUSIVE 鎖時，
之後進來的所有讀寫都得排在它後面，所以一個慢請求加上一句沒有 timeout 的 DDL，就等於整個服務停住。

`transactional-lock=false` 已經設在 [application.properties](../../backend/src/main/resources/application.properties)：
產品現在還沒有 CONCURRENTLY 的 migration，先設好，免得第一次建大表索引時才在部署途中卡住。

#### 實測到的坑

**1. `CREATE INDEX CONCURRENTLY` 會讓 Flyway 卡住不動。** `pg_stat_activity` 看到的是：

```
 state               | wait_event | query
---------------------+------------+--------------------------------------------
 idle in transaction | ClientRead | SELECT COUNT(*) FROM pg_namespace WHERE ...   ← Flyway 拿著鎖的連線
 active              | virtualxid | CREATE INDEX CONCURRENTLY ...                 ← 等上面那個交易結束
```

Flyway 預設用**交易層級**的 advisory lock（防止兩個 app 同時跑 migration），而 CONCURRENTLY 要等所有進行中的交易結束 ——
包括 Flyway 自己拿著鎖的那一個。改成連線層級的鎖之後兩秒內跑完
（[Flyway 文件](https://documentation.red-gate.com/fd/flyway-postgresql-transactional-lock-setting-277579114.html)也是這樣建議）。
整合測試自己呼叫 Flyway 的也要同一個設定：漏掉的那支不會失敗，而是整個整合測試卡到逾時。

**2. `CONCURRENTLY` 的檔案裡不能有 `SET`。**
Flyway 偵測到 CONCURRENTLY 就把整支放到交易外面跑，而 `SET` 被它歸類成交易內的句子，混在一起直接報錯
（`Detected both transactional and non-transactional statements within the same migration`）。所以那支沒有 `lock_timeout`，
用 `squawk-ignore` 標明原因：CONCURRENTLY 要的 SHARE UPDATE EXCLUSIVE 鎖排隊時不擋一般讀寫，沒有 timeout 的代價小得多。

**3. `squawk-ignore` 只管它的下一行。** 寫成 `ALTER TABLE t` 換行 `DROP COLUMN c`，違規算在第二行，ignore 沒作用。
要放行的那句寫成一行。

## 危險的變更和安全的做法

「危險」指的是：新舊版本同時在線時舊版會壞，或者會長時間鎖住整張表。CI 的 squawk（[ci/migrations.sh](../../ci/migrations.sh)）會擋下左欄的寫法。

| 變更 | 為什麼危險 | 安全的做法 | squawk 規則 |
|---|---|---|---|
| 改欄位名稱 | 舊版的 SQL 還在用舊名字，立刻壞 | 照上面 R1～R5 | `renaming-column` |
| 改型別 | 重寫整張表（ACCESS EXCLUSIVE）；舊版送的值可能被默默換算錯（例如 `TIMESTAMP` → `TIMESTAMPTZ`，舊版送的不帶時區的值會用連線的時區換算） | 同上：新欄位用新型別 | `changing-column-type` |
| 加 NOT NULL、沒有預設值 | 舊版的 INSERT 不認得這個欄位，全部失敗 | 先加成可為 NULL（或給常數預設值，PostgreSQL 11 起不重寫表）→ 程式開始寫 → 等舊版下線 → backfill → `CHECK NOT VALID` → `VALIDATE` → `SET NOT NULL` | `adding-required-field` |
| 對既有欄位 SET NOT NULL | 拿著 ACCESS EXCLUSIVE 掃整張表 | 先有驗證過的 `CHECK (col IS NOT NULL)`，SET NOT NULL 就不掃表 | `adding-not-nullable-field` |
| 放寬 NOT NULL | 讀的人可能沒料到 NULL | 確定第一筆 NULL 出現時，不懂 NULL 的版本都已下線；`squawk-ignore` 寫理由 | `ban-drop-not-null` |
| 刪欄位、刪表 | 舊版還在讀寫，立刻壞 | 先發一版不碰它的程式，等那之前的版本都不在線、也不會退回，下一版才刪 | `ban-drop-column`、`ban-drop-table` |
| 加 CHECK、外鍵 | 拿著鎖掃整張表驗證既有資料 | `ADD CONSTRAINT ... NOT VALID`，**另一支** migration 再 `VALIDATE` | `constraint-missing-not-valid`、`adding-foreign-key-constraint` |
| 建索引 | 一般的 CREATE INDEX 整段時間擋寫入 | `CREATE INDEX CONCURRENTLY`，自己一支、`DROP INDEX CONCURRENTLY IF EXISTS` 開頭（失敗會留下 INVALID 的半成品） | `require-concurrent-index-creation` |
| CHECK 允許的值多一個（例如 `Category` 多一類） | 新版寫了新值，舊版讀到 `Category.valueOf` 失敗，含有那筆行程的整段月曆都讀不出來 | 先發一版「讀得懂新值」的程式，下一版才放寬 CHECK、開始寫新值 | 抓不到（要懂程式怎麼讀） |
| 任何 ALTER TABLE | 拿不到鎖時會排隊，後面所有讀寫這張表的查詢也排在它後面 —— 等於停機 | `SET LOCAL lock_timeout = '5s'`：等不到就整支 rollback，部署失敗，比卡住所有人好 | `require-lock-timeout` |
| 大量 UPDATE（backfill） | 一句改幾百萬列：長時間佔著列鎖、大量 WAL | 分批（每批幾千列、各自 commit），放在 migration 外面的工作跑 | `require-statement-timeout` 只提醒設上限 |
| 改已經跑過的 migration | Flyway 的 checksum 對不上，已經跑過的資料庫一啟動就失敗 | 永遠加一支新的 | `ci/migrations.sh` 第 1 道 |

## Migration 檢查清單

寫一支新的 migration 時，PR 送出前逐條看過：

- [ ] **這是哪一次發布？要等的前提成立了嗎？** 寫在檔案開頭：哪個版本已經不在線上、也不會被退回
- [ ] **上一版的程式在這支跑完之後還能正常讀寫嗎？** 照著上一版的 SQL 想一遍：INSERT 少給了哪個欄位、SELECT 讀了哪個欄位、`valueOf` 會不會遇到不認得的值
- [ ] **退回上一版的話呢？** 新版寫進去的資料，上一版讀得懂嗎
- [ ] 雙寫、切讀寫在 adapter，沒有放進 trigger；R4、R5 時記得把過渡的程式碼拿掉
- [ ] backfill 以舊欄位為準蓋過不一致的，不只補 NULL
- [ ] 沒有改名、改型別、刪欄位、刪表。真的要刪：`squawk-ignore` 的註解寫了是從哪一版起沒人讀寫
- [ ] 新欄位是可為 NULL 或有常數預設值
- [ ] constraint 是 `NOT VALID`，`VALIDATE` 在另一支
- [ ] 索引是 `CONCURRENTLY`、自己一支、`IF EXISTS`／`IF NOT EXISTS`
- [ ] 開頭有 `SET LOCAL lock_timeout`、`SET LOCAL statement_timeout`（`CONCURRENTLY` 那支除外）
- [ ] 一支只做一件事：DDL 和大量 UPDATE 分開，鎖才不會疊在一起
- [ ] 有整合測試對真的 PostgreSQL 跑過，看的是程式讀到什麼、不是 schema 的長相：
      同時在線的兩版 adapter 讀得到彼此寫的資料（寫法參考[演練紀錄](#演練紀錄)的 `ExpandContractDrillTest`）
- [ ] `ci/migrations.sh` 綠燈

## 參考資料

- [squawk 規則清單](https://squawkhq.com/docs/rules)、[CLI 與設定](https://squawkhq.com/docs/cli)：用的是 2.66.0（2026-09-23）
- [Flyway：postgresql.transactional.lock](https://documentation.red-gate.com/fd/flyway-postgresql-transactional-lock-setting-277579114.html)
- GitLab：[部署後才跑的 migration](https://docs.gitlab.com/development/database/post_deployment_migrations/)、[分批的背景 migration](https://docs.gitlab.com/development/database/batched_background_migrations/)
- [strong_migrations](https://github.com/ankane/strong_migrations)（Rails）：各種危險變更的分次部署做法
- [pgroll（Xata）](https://github.com/xataio/pgroll)
- PostgreSQL 18 文件：[ALTER TABLE](https://www.postgresql.org/docs/18/sql-altertable.html)（哪個子命令拿哪種鎖、SET NOT NULL 何時可以不掃表）、
  [CREATE INDEX 的 CONCURRENTLY](https://www.postgresql.org/docs/18/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY)、
  [明確的鎖](https://www.postgresql.org/docs/18/explicit-locking.html)
