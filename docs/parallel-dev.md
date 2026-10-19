# 平行開發：好幾個 worktree 同時跑不打架

同時開好幾個 worktree（平行做好幾件事）時，各自啟動前後端、跑 E2E、跑 CI，不會搶埠、不會改到彼此的資料、電腦也不會卡死（#109）。

| 會打架的東西 | 以前 | 現在 | 在哪裡 |
|---|---|---|---|
| 後端的埠 | 固定 8090，第二個 bootRun 起不來 | 8090 空著就用，被佔了交給系統挑；實際的埠寫進 `backend/build/application.port` | `backend/build.gradle`（bootRun）、`Application.java` |
| 前端 dev server 轉給誰 | 寫死 8090（要改得手動設 `EAT_API`） | 讀**自己這個資料夾**的 `application.port`，每個請求重讀；後端不在就說「找不到後端」 | `web/vite.config.ts`、`web/dev/backendTarget.ts` |
| 前端 dev server 的埠 | 5173，被佔了自動往上找（vite 本來就會） | 一樣，寫明在設定裡 | `web/vite.config.ts` |
| E2E 的 `vite preview` | 寫死 4173 | 每次挑一個空的埠 | `web/playwright.config.ts` |
| 開發用的資料庫 | 大家都連 `talkcal` | 同一個 PostgreSQL，每個 worktree 一個 database | `backend/build.gradle`、`application.properties` |
| CPU | Gradle、Vitest 預設開「核心數」個 worker，Playwright 開一半，CI 容器不限 | 本機各限 4 個；CI 容器限 2 顆 CPU，裡面的工具照這個數開 | `backend/gradle.properties`、`web/vitest.config.ts`、`web/playwright.config.ts`、`ci/run.sh`、`ci/checks.sh` |
| CI 的 Gradle 快取 | 可寫的共用 volume，兩個 CI 同時跑會搶鎖失敗 | 共用的唯讀、各自的可寫；新下載的跑完再併回去 | `ci/run.sh` |

## 1. 埠

**後端**：`./gradlew bootRun` 先看 8090 空不空，空著就用（swagger、`perf/` 的 k6、文件寫的都是 8090，主資料夾照舊），
被佔了就設 `SERVER_PORT=0` 讓系統挑。實際拿到的埠由 Spring Boot 內建的 `WebServerPortFileWriter` 寫進 `backend/build/application.port`，
`ApplicationPidFileWriter` 寫 pid 到 `application.pid`。這兩個 writer 只在 bootRun（設了 `PORTFILE`）時才掛上去，容器、雲端不寫檔。

> Spring Boot 4 拿掉了 `context.listener.classes`（`DelegatingApplicationListener` 不在了），所以 writer 是在 `Application.main` 裡掛的。

**前端 dev server**：vite 的 proxy 每個 `/api` 請求都重讀一次 `../backend/build/application.port`。每個 worktree 有自己的 `backend/build/`，
所以只會接到自己這份後端。vite 的 proxy 本身只有啟動時給一次的 `target`，這裡把它的 `web()` 包一層，每次帶上當下的 target。

**為什麼還要看 pid**：後端被硬砍（關掉終端機、工作管理員結束）時沒機會刪檔，舊的埠號留在檔案裡。
那一號之後可能被**別的 worktree 的後端**佔走，照舊檔轉過去，就會默默接到別人的後端、看到別人的資料庫。
所以 pid 已經不在，就當作沒有後端，回 `502 {"error": "找不到後端：…"}`，畫面上的錯誤訊息就是這句（前端的 `src/http.ts` 會顯示後端回的 `error`）：

![後端不在時，登入頁顯示「找不到後端：這個資料夾上次的 bootRun（埠 8090）已經結束了」](parallel-dev/no-backend.jpg)

要接到別台（例如 deploy/ 的 18090）就設 `TALKCAL_API=http://localhost:18090`（取代原本的 `EAT_API`）。

**E2E**：`playwright.config.ts` 每次跑先挑一個空的埠給 `vite preview`。設定檔每個 worker 程序都會重新載入一次，所以挑好的埠放進環境變數 `E2E_PORT`，
worker 繼承同一個值。自己挑的埠一律從頭起 preview，不重用已經開著的（挑完到起來之間，那一號可能剛好被別的 worktree 拿走）。

**代價**：Google、GitHub 登入登記的回呼網址是 `localhost:5173`，拿到 5174 以後的那個 worktree 只能用帳密登入。
要兩邊都能用 Google 登入，得在 Google Cloud Console 多登記幾個埠，這裡沒做。

## 2. 資料庫

開發時 `bootRun` 都連 deploy/ 那台 PostgreSQL。兩個做法比較：

| | 同一個 PostgreSQL、每個 worktree 一個 database（**選這個**） | `COMPOSE_PROJECT_NAME` 每個 worktree 起一整套容器 |
|---|---|---|
| 多吃的資源 | 幾 MB（一個空 database） | 一套 postgres 容器；要整套（app、web）的話還有模型 |
| 模型 | 共用主機上那一個 | GPU 只有一張（12GB VRAM），兩個 Qwen3-8B 放不下，還是得共用 |
| 主機上的埠 | 不用動 | 5432、8080、18080、18090 全都要參數化 |
| Alloy／Grafana | 不受影響 | Alloy 只抓 `talkcal-deploy` 這個 project，另一套看不到 |
| 隔離程度 | database 層：資料、表、Flyway 紀錄都分開；連線數、記憶體共用 | 容器層，完全分開 |

開發時要隔離的只有「資料」，所以選 database 層。名字從資料夾名稱來：主資料夾 `talkcal` 還是原本的 `talkcal`（資料不搬），
`talkcal-109b` → `talkcal_109b`、`.claude/worktrees/109-parallel-dev-f0ff1b` → `talkcal_109_parallel_dev_f0ff1b`。
第一次 bootRun 時用 `docker compose exec postgres createdb` 自動建（透過 compose 的服務名稱，不靠容器名稱），Flyway 接著建表。
`DB_NAME` 有設就照它。

測試本來就隔離了：整合測試用 Testcontainers，每次一個全新的 PostgreSQL。

**`container_name` 留著**：deploy/ 這套定位是「一台機器一套」（GPU、固定的埠、Alloy 用 project 名稱找它），
固定的容器名稱讓 `docker exec talkcal-postgres` 這類指令、文件、`ops/` 的 log 標籤都穩定。
有人用別的 `COMPOSE_PROJECT_NAME` 想起第二套時，會**直接失敗**，不會默默接到同一組容器：

```
$ docker compose -p talkcal109probe -f deploy/compose.yaml up -d postgres
Error response from daemon: Conflict. The container name "/talkcal-postgres" is already in use by container "ee115e83…"
```

**要記得的**：worktree 刪掉之後它的 database 還在，清掉：`docker compose -f deploy/compose.yaml exec postgres dropdb -U talkcal talkcal_<資料夾名稱>`。
新的 worktree 裡沒有 `deploy/.env`（不進 git），要從主資料夾複製一份，bootRun 才讀得到密碼。

## 3. CPU

### 先量

| 量什麼 | 結果 |
|---|---|
| 這台機器 | 32 個邏輯核心；但 Docker（WSL2 VM）被 `%UserProfile%\.wslconfig` 的 `processors=4` 限在 **4 顆**。所有 CI 容器、Testcontainers 的 PostgreSQL、deploy/、ops/ 都擠這 4 顆 |
| 一次 CI 用掉多少（不限 CPU，`docker stats` 每 5 秒抽一次） | 峰值約 2.7 顆（編譯、前端打包），平均約 1.5 顆；大段時間在等 Testcontainers 的容器起來，CPU 用不滿 |
| 限 2 顆 vs 不限（4 顆），單獨跑 | 101 秒 vs 97 秒，只慢 4% |
| 本機 E2E：Playwright 預設（16 個 worker）vs 4 個 | 9.1 秒 vs 10.2 秒；測試只有 13 個，開再多瀏覽器也快不了 |

（2026-10-19 量的，模型沒開，跳過了 25 個要模型的整合測試）

### 再設上限

| 工具 | 預設 | 改成 | 理由 |
|---|---|---|---|
| `ci/run.sh` 的 `--cpus` | 不限 | **2**（`CI_CPUS` 可改） | 一次 CI 平均用不到 2 顆，限 2 只慢 4%；VM 只有 4 顆，兩個 CI 同時跑剛好各一半 |
| Gradle `--max-workers`（CI 裡） | 容器看到的核心數 | `CI_CPUS` | `--cpus` 只限總量、不改容器看到的核心數，照看到的數開 worker 會開出一堆搶同一點 CPU 的程序 |
| Vitest `--maxWorkers`、Playwright `workers`（CI 裡） | 同上 | `CI_CPUS` | 同上 |
| Gradle `org.gradle.workers.max`（本機） | 32 | 4 | 只有一個模組、測試一次一個 fork，CI 量到最多用兩顆多 |
| Vitest `maxWorkers`（本機） | 31 | 4 | 測試檔只有幾個，全部不到一秒 |
| Playwright `workers`（本機） | 16 | 4（`E2E_WORKERS` 可改） | 上面量的：16 個只快 1 秒，瀏覽器卻多 4 倍 |

Testcontainers 開的 PostgreSQL 是 CI 容器的「鄰居」，不在 `--cpus` 的限制裡；它們很輕（每個整合測試類別一個、閒著居多）。

## 4. CI 的 Gradle 快取：同時跑會搶鎖

**實際發生過**（2026-10-19，47-adr、107-pnpm 兩個 worktree 同時跑 CI，重現在下面的實測）：

```
> Timeout waiting to lock journal cache (/opt/gradle-home/caches/journal-1). It is currently in use by another process.
  Lock file: /opt/gradle-home/caches/journal-1/journal-1.lock
BUILD FAILED in 1m 5s
```

**原因**：以前整個 `caches/` 是一個可寫的共用 volume（`talkcal-ci-gradle`），好幾個 CI 容器同時掛。
Gradle 的跨程序鎖要「拿著鎖的那個程序」放手，請它放手的通知走 `127.0.0.1` 的 UDP；
兩個 CI 在不同容器、各有各的 `127.0.0.1`，通知送不到，另一個只能乾等 60 秒逾時。

**做法**：Gradle 給短命 CI 機器用的[唯讀依賴快取](https://docs.gradle.org/current/userguide/dependency_caching.html#sec:shared-readonly-cache)（`GRADLE_RO_DEP_CACHE`）。

1. 共用的 volume `talkcal-ci-gradle-ro` **唯讀**掛進去。唯讀快取不加鎖，幾個容器同時讀都沒事
2. 唯讀快取裡沒有的（新加的依賴）照樣下載，寫進容器自己的 `caches/`，跟著容器一起丟掉
3. 跑完把容器裡新下載的 `modules-2` 併回共用的那份：**只新增、不改已經有的檔案**，每個檔先解到旁邊再 `mv`（同一個檔案系統裡 `mv` 是一瞬間的事）。
   別的 CI 正在讀也只會看到「沒有」或「完整的」檔，不會讀到寫一半的 jar；兩個 CI 同時併同一個檔，內容一樣，誰的留下來都對

另外考慮過的：

| 做法 | 為什麼沒選 |
|---|---|
| 排隊（一次只跑一個 CI） | 能平行跑本來就是目的 |
| 每個 worktree 一個可寫的 volume | 每個新 worktree 第一次都要重新下載幾百 MB；volume 隨 worktree 越積越多，要另外清 |
| `--network host` 讓鎖的通知送得到 | Testcontainers、`host.docker.internal` 的連法都要跟著改，Docker Desktop 上的 host 網路行為也跟 Linux 不同 |

代價：容器裡的 `transforms/`、`jars-9/` 這些每次重算（第一次跑完、快取熱了以後，整個 CI 約 100 秒，見下面）。
npm 的快取（`talkcal-ci-npm`）照舊共用可寫：npm 的快取是內容定址、寫入是原子的，設計上就允許好幾個程序同時用。

## 5. 實測：兩個 worktree 同時跑

2026-10-19 在這台機器上：A = 這個分支的 worktree（`.claude/worktrees/109-parallel-dev-f0ff1b`），
B = 同一個 commit 開在專案同層的 `talkcal-109b`。

### dev server

| 步驟 | A | B |
|---|---|---|
| `./gradlew bootRun` | 8090，database `talkcal_109_parallel_dev_f0ff1b`（第一次，自動建） | 印「8090 已經有人用了」，系統挑了 63078，database `talkcal_109b` |
| `npx vite` | 5173 | 「Port 5173 is in use, trying another one」→ 5174 |
| 從 A 的 5173 註冊 `only_in_a` | 201 | — |
| 用同一組帳密從 B 的 5174 登入 | — | **401 帳號或密碼錯誤**（A 的帳號不在 B 的資料庫） |
| 從 A 的 5173 登入（對照） | 204 | — |

**反例：A 的後端被硬砍，B 重啟後搶到 8090**。A 的 `application.port` 還寫著 8090（硬砍沒機會刪檔），B 的也是 8090：

```
A(5173): 502 {"error":"找不到後端：這個資料夾上次的 bootRun（埠 8090）已經結束了。到 backend/ 重新 ./gradlew bootRun"}
B(5174): 200 {"providers":["google"]}
```

改之前（proxy 寫死 8090）A 的前端會直接接到 B 的後端，看到 B 的資料庫，而且不會有任何訊息。
沒有埠檔、沒有 pid 檔、埠檔內容壞掉這幾種情況在 `web/dev/backendTarget.test.ts` 裡各有一題。

### E2E（`npm run e2e`，本機）

| | 時間（Playwright 自己報的） |
|---|---|
| A 單獨跑 | 8.6 秒、9.3 秒 |
| A、B 同時跑 | A 11.5、B 10.3 秒；A 9.4、B 9.8 秒 |

都是 13 passed，沒有衝突。

**反例：A 的 `vite preview` 佔著 4173，B 用 master 的設定跑**（先把 B 的 `dist/` 刪掉）：

| B 的設定 | 結果 |
|---|---|
| master 的，本機（`reuseExistingServer: true`） | **13 passed，但 B 根本沒有 `dist/`**：測的是 A 的 build，綠燈是假的 |
| master 的，`CI=1` | `Error: http://localhost:4173 is already used` |
| 這個分支的 | 13 passed，自己挑埠、自己 build（B 的 `dist/` 出現了） |

### CI（`ci/run.sh`）

| 情況 | A | B |
|---|---|---|
| **改之前**：master 的 `run.sh`，同時跑 | 通過，195 秒 | **71 秒失敗**：`Timeout waiting to lock journal cache` |
| 改之後，同時跑，唯讀快取還是空的（第一次） | 通過，307 秒；併回 1418 個檔 | 通過，318 秒；併回時都已經有了，新增 0 個 |
| 改之後，同時跑，快取熱了 | 通過，117 秒 | 通過，118 秒 |
| 改之後，A 單獨跑（對照） | 通過，101 秒 | — |
| 改之後，A 單獨跑、`CI_CPUS=4`（不限） | 通過，97 秒 | — |

兩個同時跑各慢 16%（101 → 117 秒），沒有衝突。每個 CI 容器的 CPU 峰值約 1.8～2.1 顆，`--cpus 2` 有生效。
「改之前」A 的 195 秒有開模型（模型測試真的跑），之後幾次模型沒開、跳過 25 題，所以不能直接跟後面比。

## 還沒處理的

- **Docker 只有 4 顆 CPU**：`.wslconfig` 是這台電腦的設定，不在 repo 裡。要同時跑三個以上的 CI，先把 `processors` 調高（Docker Desktop 重啟才生效）
- **OAuth 回呼網址只登記了 5173**：第二個以後的 dev server 只能用帳密登入
- **worktree 的 database、`deploy/.env` 要手動處理**：刪 worktree 時 database 不會跟著刪；新 worktree 要複製 `deploy/.env`
- **pid 被重複使用**：硬砍之後，剛好有別的程序拿到同一個 pid 時，vite 會以為後端還在。機率很低，而且最壞的結果是連不上（回「連不上後端」），
  除非那一號埠剛好也被別的 worktree 的後端佔走
