# ci —— 持續整合（CI）

每次改動都在**乾淨的容器**裡自動檢查一遍：後端的單元測試、架構規則、整合測試（編譯時 Error Prone 一起跑），前端的 lint（ESLint）、單元測試（Vitest）、型別檢查、打包、E2E（Playwright 開真的 Chromium）。
本機（push 之前）和 GitHub（push 之後）跑的是**同一支 script、同一個映像檔**，所以本機綠就代表 GitHub 綠。

整條 CI → CD 的流程圖和每一關擋什麼，見 [docs/architecture.md](../docs/architecture.md#cicd從-commit-到線上)。

## 怎麼跑

```bash
ci/run.sh                               # 手動跑一次，測 HEAD
git config core.hooksPath .githooks     # 啟用 pre-push hook（每個 clone 做一次）
git push --no-verify                    # 確定要跳過檢查時
```

要先開著 Docker Desktop。llama-server 有開的話，模型相關的測試會真的跑；沒開就跳過，摘要會列出來。
第一次跑要 build 映像檔（含 Gradle 本體）、下載依賴，比較慢；之後映像檔有快取、依賴放在 volume `talkcal-ci-gradle`、`talkcal-ci-pnpm`（pnpm store）裡，就快了。
結果：摘要在 `ci/build/summary.md`，失敗時的 HTML 報告在 `ci/build/reports/`；覆蓋率報告在 `ci/build/reports/jacoco/`、`ci/build/web-coverage/`（GitHub 上是 `coverage` artifact，怎麼看見 [docs/test-coverage.md](../docs/test-coverage.md)）。

## 架構：檢查內容只寫一次，平台只是入口

```
.github/workflows/ci.yml ─┐
                          ├──▶ ci/run.sh ──▶ 容器（ci/Dockerfile）裡跑 ci/checks.sh
.githooks/pre-push ───────┘     在主機上       在容器裡
```

| 檔案 | 回答的問題 | 換平台時 |
|---|---|---|
| `checks.sh` | **要檢查什麼** | 不動 |
| `Dockerfile` | **在什麼環境檢查**（JDK 25、Node 22、Gradle 本體、Playwright 的 Chromium） | 不動 |
| `run.sh` | 怎麼把程式碼放進容器、跑完怎麼拿回結果 | 不動 |
| `.github/workflows/ci.yml` | **什麼時候、在哪台機器跑**，結果貼到哪 | 換成 `.gitlab-ci.yml`、`bitbucket-pipelines.yml`、`Jenkinsfile` |

跟後端的六角架構同一個道理：`checks.sh` 是核心，各平台的設定檔是 adapter。
`ci.yml` 若自己寫一串 `setup-java`、`./gradlew test`，就等於把業務規則寫進 adapter ——
換到 GitLab 要整份重寫，本機的 hook 也得另外抄一份，兩邊遲早會不一樣。

## 為什麼在容器裡跑，而不是直接用主機

直接在主機跑會遇到的事：

| 問題 | 例子 | 容器怎麼解決 |
|---|---|---|
| 測到的不是要推的東西 | 忘了 `git add` 的檔案讓本機測試過了，推上去才紅 | 用 `git archive` 只複製那個 commit 進去 |
| 環境不一樣 | 主機預設是別版的 JDK、Node，或有個人的 Gradle 設定 | JDK、Node 版本只定義在 `Dockerfile` |
| 殘留狀態 | 舊的 `build/`、`node_modules` 讓結果看起來沒事 | 每次都是新容器，跑完就刪 |

代價和處理：

| 代價 | 處理 |
|---|---|
| 每次全新環境，東西要重新下載 | 很少變的（JDK、Node、Gradle 本體）烤進映像檔；常變的（Java 函式庫、前端套件）放具名 volume，跨次保留；程式碼兩邊都不放 |
| Windows 把資料夾掛進容器很慢（實測約 20 MB/s） | 不掛資料夾，用 `git archive` 複製 |
| 整合測試要開 PostgreSQL（Testcontainers） | 把主機的 `docker.sock` 接進來，PostgreSQL 開在主機的 Docker 上、跟 CI 容器並排，不在容器裡再包一層 |
| 模型測試要連 llama-server | 經 `host.docker.internal:8080` 連主機上的；要連別台就設 `SPRING_AI_OPENAI_CHAT_BASEURL` |

## 為什麼本機和 GitHub 都要跑

| | 本機（pre-push） | GitHub Actions |
|---|---|---|
| 有沒有模型 | 有（llama-server 開著時） | 沒有 GPU，模型測試跳過 |
| 擋得住什麼 | 推之前就擋，壞掉的東西不會上去 | 不管從哪條路進來（push、PR）都會跑，不靠人記得 |
| 誰看得到 | 只有自己 | 公開：commit 旁的 ✅ ❌、README 的徽章 |

兩邊各守一道：本機補上 GitHub 跑不到的模型測試，GitHub 補上本機可以 `--no-verify` 繞過的那一道。

## 祕密掃描

`ci/secrets.sh` 用 gitleaks 掃 **git 歷史**，不是掃最新的檔案：key 在一個 commit 加進去、下一個刪掉，
檔案看起來乾淨，推上去之後任何人 clone 都拿得到。所以它不在 `checks.sh` 裡 —— 那邊只有某個 commit 的檔案，沒有 `.git`。

```bash
ci/secrets.sh HEAD                   # 掃 HEAD 往回的整個歷史（不帶參數也是這個）
ci/secrets.sh HEAD --not --remotes   # 只掃還沒推上去的（pre-push 用的就是這個）
```

| | pre-push | GitHub push protection | CI 的 `secrets` job |
|---|---|---|---|
| 掃哪些 commit | 這次要推的 | 這次推上來的 | 這次要測的 commit 往回的整個歷史 |
| 認得什麼 | gitleaks 的規則（各家 key ＋通用 token） | 只認各家服務的 key 格式 | 同 pre-push |
| 跳得過嗎 | `--no-verify` | 可以在 GitHub 上填理由放行 | 不行（必過檢查） |
| 抓到時 | key 還在本機，改掉再推 | 被拒收 | **已經公開**，要作廢重發 |

CI 只掃 `HEAD` 走得到的 commit，不掃其他分支：`fetch-depth: 0` 會把所有分支一起抓下來，
而 gitleaks 沒指定範圍時會用 `git log --all`，結果別的 PR 分支上一個誤判，所有 PR 的 `secrets` 都紅燈（#180）。
在 PR 上 `HEAD` 是 master 加這個 PR 的 merge commit，所以 master 的歷史和這個 PR 的每個 commit 都會掃到；
別的分支由它自己的 PR 負責。

pre-push 先掃祕密、再跑測試：掃描只要幾秒，而且測試紅了可以再推一次修，key 送出去就收不回來。
### GitHub 已經會擋，為什麼還要 gitleaks

老實說：這個 repo 用到的 key（AWS、OpenAI 這類）GitHub 都認得，公開 repo 的 push protection 和背景掃描已經擋住大部分會發生的事。
gitleaks 補的是邊界：

- **在本機就擋**：GitHub 擋的時候 key 已經送出這台電腦；pre-push 擋的時候還沒
- **通用規則**：自己系統發的 token、私鑰檔，GitHub 不認得
- **放行要留痕跡**：GitHub 被擋時在網頁上填個理由就能放行；這裡的 CI 是必過檢查，跳不過
- **不綁平台**：私人 repo 要付費才有 GitHub 這套；換到 GitLab、公司內部的 Git 也照樣有效

代價是 pre-push 多約 1 秒、CI 多一個並行的 job，所以留著。

被誤判時（測試用的假 key），那一行加註解 `gitleaks:allow`，或把報告裡的 Fingerprint 寫進 `.gitleaksignore`。

## 漏洞掃描

`ci/vulns.sh` 用 Trivy 掃 build 好的映像檔：基底映像檔的作業系統套件、jar 裡的 Java 函式庫一次掃到。
有 HIGH、CRITICAL 而且已經有修補版本的，就 exit 1 —— 上游還沒修的，我們也沒得升，擋了也沒事可做。

```bash
docker compose -f deploy/compose.yaml build app web
ci/vulns.sh talkcal/app:local talkcal/web:local postgres:18.6-alpine
```

| 時機 | 為什麼 |
|---|---|
| 每個 PR、push 到 master | 改動帶進新的套件版本、換了基底映像檔 |
| 每天台灣 09:00（排程，只跑這個 job） | 程式碼沒變，但新的 CVE 公布了。失敗時 GitHub 寄信 |
| 手動（Actions 頁面的 Run workflow） | 大漏洞剛公布，想馬上知道有沒有中 |

**掃到了怎麼辦**：有修補版本就升（基底映像檔重新 build、函式庫在 build.gradle 拉高版本）；
真的修不了、評估過碰不到的，寫進 `ci/trivyignore.yaml`，**一定要附理由和到期日** —— 到期後會重新報出來，逼人再看一次。

**為什麼不用 trivy-action**：Trivy 在 2026-03 被入侵過，v0.69.4 和 trivy-action 幾乎所有 tag 都被換成會偷 CI secret 的版本。
這裡跟 gitleaks 一樣直接跑映像檔，版本和 digest 都釘死，用的是事件之後的版本。

## lint

常見的錯在測試之前就紅燈（#22）。只抓「會出錯的寫法」，不管格式。

| | 工具 | 擋什麼 | 在哪跑 |
|---|---|---|---|
| 後端 | Error Prone | ERROR 等級的 bug 寫法（忘了用回傳值、`equals` 比錯型別、格式字串對不上…）讓編譯失敗；WARNING 只印出來 | 掛在 javac 上，每次編譯都跑（main、test、integrationTest、eval），`checks.sh` 自動涵蓋 |
| 前端 | ESLint（eslint-plugin-vue、@vue/eslint-config-typescript、eslint-plugin-playwright） | 沒處理的 Promise、`v-for` 沒 key、E2E 的 `expect` 忘了 `await`… | `checks.sh`：`pnpm run lint` |

```bash
cd web && pnpm run lint              # 前端
cd web && pnpm run lint --fix        # 能自動修的先修掉
cd backend && ./gradlew classes     # 後端：編譯就是檢查
```

### 選型（2026-10 查的）

挑工具時看三件事：**授權**（只用 MIT、Apache-2.0；這些都是開發工具，不會打包進 jar 或映像檔）、
**社群熱門度**（維護者、星數、下載量，最後一次發版多久以前）、**版本發布滿兩週**（新版被植入惡意程式碼通常幾天內就會被發現；
Trivy 2026-03 的事件見下面的「漏洞掃描」）。前端套件的兩週規則寫在 `web/pnpm-workspace.yaml`（`minimumReleaseAge`），`pnpm add` 自動套用，連間接依賴也算進去；`checks.sh` 的 `pnpm install --frozen-lockfile` 也照它檢查 lockfile，不滿兩週的版本進不了 master。

**後端：Error Prone，不加 SpotBugs、Checkstyle、PMD**

| | 授權 | 熱門度 | 為什麼（不）選 |
|---|---|---|---|
| Error Prone 2.50.0 | Apache-2.0 | 7.3k★，Google 維護 | javac 外掛，編譯過就是檢查過；ERROR 等級的規則誤判極少，紅了幾乎一定是 bug |
| SpotBugs | LGPL-2.1 | 4.0k★ | 編譯後掃 bytecode，跟 Error Prone 重疊大，誤判比較多，要另外維護排除清單 |
| Checkstyle | LGPL-2.1 | 9.6k★ | 檢查的是風格，那是 formatter 的事（#179） |
| NullAway | MIT | 4.1k★ | Error Prone 的外掛，要在程式碼上標 `@Nullable`，等於改動大半的檔案，先不做 |

Error Prone 只用預設：WARNING 不當成錯誤（不開 `-Werror`）。現在有 14 個警告（`StringCaseLocaleUsage` 6 個最多），
要變成必過就得先清掉，會動到不少 Java 檔，之後另外處理。
Gradle 外掛用 [net.ltgt.errorprone](https://github.com/tbroyer/gradle-errorprone-plugin)（Apache-2.0，Error Prone 官方文件指名的 Gradle 接法）。

**前端：ESLint，不是 Biome 或 oxlint**。2026 年 ESLint 10 只剩 flat config（`eslint.config.js`），
設定照 Vue 官方 `create-vue` 產生的那套。

| | 授權 | 熱門度（npm 週下載） | 為什麼（不）選 |
|---|---|---|---|
| ESLint 10 | MIT | 27.6k★，9600 萬 | Vue 專屬規則（eslint-plugin-vue，Vue 官方）和要型別資訊的規則（`no-floating-promises`）都只有 ESLint 有 |
| Biome | Apache-2.0 | 26.0k★，1100 萬 | 一支 Rust 工具同時 lint 和格式化、快很多，但沒有 Vue 專屬規則，這個專案大半是 `.vue` |
| oxlint | MIT | 23.1k★，1600 萬 | 比 ESLint 快幾十倍，但規則少、多半不能自動修；常見用法是放在 ESLint 前面當加速層，這個大小的專案 ESLint 本身只要幾秒 |

外掛：eslint-plugin-vue（MIT，Vue 官方，370 萬）、@vue/eslint-config-typescript（MIT，Vue 官方，90 萬）、
eslint-plugin-playwright（MIT，397★ 但每週 300 萬下載，E2E 用 Playwright 的專案幾乎都裝）。

### 格式化為什麼還沒做

格式化（Spotless + google-java-format、Prettier）另外在 [#179](https://github.com/Anderson-chen/talkcal/issues/179)：
要一次把整個 codebase 排好才划算，但 2026-10 時有 30 個開著的 PR，現在排會全面衝突。
只排改過的檔案（Spotless 的 ratchet）也試過：之後每個碰到舊檔的 PR 都要把整份檔案重排，邏輯和格式混在同一個 commit，所以不採用。
等開著的 PR 消化完再一次做。

參考：
[ESLint vs Biome vs Oxlint 2026（pkgpulse）](https://www.pkgpulse.com/guides/biome-vs-eslint-vs-oxlint-2026)、
[Biome vs ESLint vs Oxlint 2026（DEV）](https://dev.to/royce_fabbd83cb268312e928/biome-vs-eslint-vs-oxlint-js-linters-2026-408h)、
[gradle-errorprone-plugin](https://github.com/tbroyer/gradle-errorprone-plugin)。
星數、下載量是 2026-10-20 的數字。

## 授權檢查

會打包出去的依賴不能是 GPL、AGPL、LGPL，也不能是認不出來的授權，在 `checks.sh` 裡跑（本機 pre-push 也會跑到）：

| 範圍 | 怎麼查 | 規則 |
|---|---|---|
| 後端 `runtimeClasspath` | Gradle 的 `checkLicense`（`./gradlew check` 也會跑） | 允許清單 `backend/allowed-licenses.json` |
| 前端 `dependencies` | `ci/licenses.sh`：另外裝一份只有 `dependencies` 的 node_modules，用 Trivy 讀（npm、pnpm 都可以） | Trivy 的分類，擋 CRITICAL、HIGH、UNKNOWN，外加「沒寫授權」「一個都沒掃到」 |

為什麼兩邊工具不一樣（Trivy 讀不到 jar 的授權、讀不到 pnpm-lock.yaml 的授權，都會靜靜地通過）、
被擋了怎麼辦、反例驗證，都在 [docs/licenses.md](../docs/licenses.md)。

## DB migration 檢查

`ci/migrations.sh` 跟目標分支比，看這次動了哪些 migration（#18）：

```bash
ci/migrations.sh               # 跟 origin/master 比
ci/migrations.sh <commit>      # 跟指定的 commit 比
```

| 檢查 | 擋什麼 | 為什麼 |
|---|---|---|
| 已經在目標分支裡的 migration 不能改、刪、改名 | 偷改 V11、把 V10 改名 | Flyway 比對 checksum，已經跑過的資料庫一啟動就驗證失敗 —— 部署到一半才發現 |
| 新增的 migration 過 [squawk](https://squawkhq.com/docs/rules)（設定在 `ci/squawk.toml`） | DROP／RENAME COLUMN、改型別、加 NOT NULL 沒預設值、沒 CONCURRENTLY 的索引、ADD CONSTRAINT 沒 NOT VALID、沒設 lock_timeout／statement_timeout | 新舊版本同時在線時舊版會壞，或整張表被鎖住。每條規則的理由和安全的替代做法見 [squawk 規則清單](https://squawkhq.com/docs/rules) |

只看新增的檔案：V1～V11 已經跑過、改不了，掃出來的警告（例如 V2 的 `prefer-timestamp-tz`）也修不掉。
真的要做危險的變更（contract 刪掉沒人用的欄位），在那一句上面加 `-- squawk-ignore <規則>` 並寫理由，PR 的 diff 上看得到。

**為什麼用 squawk、不自己 grep**：grep `DROP COLUMN` 只抓得到字面，抓不到「同一個交易裡 ADD CONSTRAINT NOT VALID 又 VALIDATE，
鎖一路拿到交易結束」這種要懂鎖的問題。squawk 用 PostgreSQL 自己的 parser 解析 SQL，
規則是 PostgreSQL 各版本的鎖行為（`pg_version` 設成跟 compose 一樣）。用 npm 的 `squawk-cli`，版本釘死；
執行檔是同版本的 npm 平台套件，安裝時不另外下載。

**反例**（2026-10-16，在暫時的分支上做、沒有推上去）：

| 造出來的 migration | 結果 |
|---|---|
| V11 結尾加一行註解、V10 改名 | 第 1 道擋下，列出這兩支 |
| `RENAME COLUMN note TO memo` | `renaming-column` |
| `DROP COLUMN note` | `ban-drop-column` |
| `ALTER COLUMN start_at TYPE TIMESTAMPTZ` | `changing-column-type` |
| `ADD COLUMN reminder_minutes INTEGER NOT NULL`（沒預設值） | `adding-required-field` |
| `CREATE INDEX`（沒 CONCURRENTLY） | `require-concurrent-index-creation` |
| `ADD CONSTRAINT ... CHECK (...)`（沒 NOT VALID） | `constraint-missing-not-valid` |
| `ADD COLUMN color TEXT`，前面沒 SET lock_timeout | `require-lock-timeout`、`require-statement-timeout` |
| 同上，兩個 timeout 都設了 | 通過 |
| `DROP COLUMN IF EXISTS note`，上面有 `squawk-ignore ban-drop-column` 和理由 | 通過 |

還沒做：加進 ruleset 的必過檢查（GitHub 的設定，要手動加 `migrations`）。

## SLO 錄製規則檢查

`ci/rules.sh` 用 promtool 檢查 [ops/mimir/rules/](../ops/mimir/rules/) 的錄製規則，再跑單元測試 [ops/mimir/slo.test.yaml](../ops/mimir/slo.test.yaml)：
餵假的請求序列，比對錄出來的 SLI、burn rate。GitHub 上是自己一個 job（`rules`）。

```bash
ci/rules.sh
```

| 造出來的錯 | 結果 |
|------------|------|
| 比例的分母拿掉 `> 0` | 紅：沒流量的那段錄成 NaN，測試期望「沒資料」 |
| 規則不變 | 通過 |

還沒做：加進 ruleset 的必過檢查（要手動加 `rules`）；pre-push 也還沒接這支。

## 告警端到端測試

`ci/rules.sh` 只驗得到 Mimir 錄製規則的算式；Grafana 的告警規則（門檻、`for`、`noDataState`）、contact point、通知政策、SMTP
要真的跑起來才知道對不對。`ci/alerting.sh` 起 [ops/compose.yaml](../ops/compose.yaml) 的整組觀測加 Mailpit（假信箱），
疊上 [ci/alerting/compose.yaml](alerting/compose.yaml)：換專案名、拿掉固定的容器名稱和主機埠、Alloy 接一個只有假 app 的網路，
所以本機正在跑的那組不受影響。假 app（[ci/alerting/fake-app.py](alerting/fake-app.py)）只吐 `/actuator/prometheus`，可以切成全部回 5xx 或停掉。
真的 app 不搬進來：要 build 映像檔、AI 助理還要模型，而這裡要驗的是「指標 → Mimir → Grafana → 寄信」，不是 app。

```bash
ci/alerting.sh            # 約 15 分鐘
KEEP=1 ci/alerting.sh     # 跑完留著容器，到 localhost:13000（Grafana）、localhost:18025（Mailpit）看
```

| 步驟 | 要看到 | 實測 |
|------|--------|------|
| 正常流量 3 分鐘 | Mailpit 0 封 | — |
| 假 app 全部回 5xx | `[FIRING:n] SLO 快燒…` | 約 4 分鐘 |
| 停掉假 app | `[FIRING:n] talkcal app 掛了…` | 約 2 分鐘 |
| 假 app 起來 | `[RESOLVED] talkcal app 掛了…` | 約 5 分鐘（要等 `policies.yaml` 的 `group_interval`） |

每一步的時限寫在 `ci/alerting.sh` 的 `wait_until`，比實測多抓一倍。

GitHub 上是另一支 workflow（`.github/workflows/alerting.yml`），只在 PR 改到告警相關的路徑時跑，另外可以手動按。
不放進 `ci.yml`：GitHub 的 `paths` 只能設在整支 workflow 上，而這支 15 分鐘，不值得每個 PR 都等。
也因為不是每個 PR 都會出現，不能設成 ruleset 的必過檢查。

| 造出來的錯 | 結果 |
|------------|------|
| `rules.yaml` 的 app-down 門檻 `lt 1` 改成 `lt 0` | 紅：停掉假 app 5 分鐘內沒收到「app 掛了」 |
| 規則不變 | 通過 |
| PR 只改了這些路徑以外的檔案 | 不跑 |

刻意不驗：慢燒（視窗要好幾小時，算式靠 `ci/rules.sh`）、真的信箱收不收得到、會不會進垃圾郵件（CI 裡沒有）。

## 刻意不做的

- **GitHub 上跑 AI eval（`./gradlew eval`）**：沒有模型。本機的 pre-push 改到 prompt 或模型相關的檔案時才跑（`RUN_EVAL=1`），GitHub 上只確定它編得過。見 [#8 的「AI 品質回歸（eval）」段落](https://github.com/Anderson-chen/talkcal/issues/8)。
- **k6 壓測**：要真模型，CI 量不出有意義的數字。見 [perf/README.md](../perf/README.md)。
- **GitHub 上的依賴快取**：每次都是新機器、重新下載，比較慢。先求跟本機一致，嫌慢再把 `/cache` 接上 `actions/cache`。
- **自架 runner 讓 GitHub 也跑模型測試**：公開 repo 接自架 runner，別人的 PR 會在這台電腦上執行程式碼；
  要做的話只能接自己的 push，而且要另外做安全設定。目前由本機的 pre-push 補這一塊。
