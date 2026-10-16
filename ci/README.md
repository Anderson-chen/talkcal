# ci —— 持續整合（CI）

每次改動都在**乾淨的容器**裡自動檢查一遍：後端的單元測試、架構規則、整合測試（編譯時 Error Prone 一起跑），前端的格式（Prettier）、lint（ESLint）、單元測試（Vitest）、型別檢查、打包、E2E（Playwright 開真的 Chromium）。
Java 的格式要看 git 歷史，另外由 `ci/format.sh` 檢查（見[格式與 lint](#格式與-lint)）。
本機（push 之前）和 GitHub（push 之後）跑的是**同一支 script、同一個映像檔**，所以本機綠就代表 GitHub 綠。

整條 CI → CD 的流程圖和每一關擋什麼，見 [docs/architecture.md](../docs/architecture.md#cicd從-commit-到線上)。

## 怎麼跑

```bash
ci/run.sh                               # 手動跑一次，測 HEAD
git config core.hooksPath .githooks     # 啟用 pre-push hook（每個 clone 做一次）
git push --no-verify                    # 確定要跳過檢查時
```

要先開著 Docker Desktop。llama-server 有開的話，模型相關的測試會真的跑；沒開就跳過，摘要會列出來。
第一次跑要 build 映像檔（含 Gradle 本體）、下載依賴，比較慢；之後映像檔有快取、依賴放在 volume `talkcal-ci-gradle`、`talkcal-ci-npm` 裡，就快了。
結果：摘要在 `ci/build/summary.md`，失敗時的 HTML 報告在 `ci/build/reports/`。

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
| 每次全新環境，東西要重新下載 | 很少變的（JDK、Node、Gradle 本體）烤進映像檔；常變的（Java 函式庫、npm 套件）放具名 volume，跨次保留；程式碼兩邊都不放 |
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
ci/secrets.sh                        # 掃整個歷史
ci/secrets.sh HEAD --not --remotes   # 只掃還沒推上去的（pre-push 用的就是這個）
```

| | pre-push | GitHub push protection | CI 的 `secrets` job |
|---|---|---|---|
| 掃哪些 commit | 這次要推的 | 這次推上來的 | 整個歷史 |
| 認得什麼 | gitleaks 的規則（各家 key ＋通用 token） | 只認各家服務的 key 格式 | 同 pre-push |
| 跳得過嗎 | `--no-verify` | 可以在 GitHub 上填理由放行 | 不行（必過檢查） |
| 抓到時 | key 還在本機，改掉再推 | 被拒收 | **已經公開**，要作廢重發 |

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

## 格式與 lint

風格交給工具排，不靠人（或 AI）記得；常見的錯在編譯前就紅燈（#22）。

| | 工具 | 擋什麼 | 範圍 | 在哪跑 |
|---|---|---|---|---|
| Java 格式 | Spotless + palantir-java-format | 縮排、換行、import 順序、沒用到的 import | **跟 `origin/master` 比改過的檔案**（ratchet） | `ci/format.sh`（CI 的 `format` job、pre-push） |
| Java 靜態分析 | Error Prone | ERROR 等級的 bug 寫法（`equals` 比錯型別、忘了用回傳值、格式字串對不上…）讓編譯失敗；WARNING 只印出來 | 全部（掛在 javac 上） | 每次編譯，所以 `checks.sh` 自動涵蓋 |
| 前端格式 | Prettier | 同上，`.ts`、`.vue`（含 template、style）、設定檔 | 整個 `web/` | `checks.sh`：`npm run format:check` |
| 前端 lint | ESLint（typescript-eslint、eslint-plugin-vue、eslint-plugin-playwright） | 沒處理的 Promise、`v-for` 沒 key、E2E 的 `expect` 忘了 `await`… | 整個 `web/` | `checks.sh`：`npm run lint` |

```bash
ci/format.sh apply                  # Java：把改過的檔案排好（= cd backend && ./gradlew spotlessApply）
cd web && npm run format            # 前端：整個 web/ 排好
cd web && npm run lint -- --fix     # 前端：能自動修的 lint 問題先修掉
```

### 為什麼 Java 只看改過的檔案，前端卻整個排好

全面格式化的代價看「同時有多少分支在改同一批檔案」：

- **Java**：實際跑過一次全面格式化，109 個檔案裡有 65 個會變（+1429／−813 行），幾乎每個都動到換行和 import 順序。
  同時進行中的分支（例如動 Flyway migration 和 repository 的那條）會全面衝突，`git blame` 也會整片指向那個格式化 commit。
  所以用 Spotless 的 `ratchetFrom 'origin/master'`：改到哪個檔案，那個檔案就要整份排好，沒碰的舊檔案不管，慢慢收斂。
  代價是要有 git 歷史才知道改了什麼 —— `checks.sh` 的容器沒有 `.git`，所以跟 `secrets.sh` 一樣另外一支、另外一個 job。
- **前端**：只有 `web/` 自己在改、檔案少（38 個），一次排好的衝突風險小，之後也不必有「改過的／沒改過的」兩套標準。

### 選型（2026-10 查的）

**Java formatter：palantir-java-format，不是 google-java-format**。兩個都是「幾乎沒有設定可調」的 formatter，差在：
palantir 是 4 格縮排、120 字寬，跟現有程式碼一樣，被 ratchet 碰到的檔案只會改真的不合格的地方；
google-java-format 是 2 格縮排，碰到的檔案每一行都會變。palantir 本來就是為了 lambda、串接呼叫比較好讀，從 google-java-format 分出來的，
Spring 這種大量 builder、stream 的程式碼正好適用。透過 Spotless 而不是各自的 Gradle 外掛：同一個入口同時管 import 順序、行尾空白，
`spotlessApply`／`spotlessCheck` 也是 Gradle 專案最常見的指令名稱。

**Java 靜態分析：Error Prone，不加 SpotBugs、Checkstyle、PMD**。

| | 怎麼跑 | 為什麼（不）選 |
|---|---|---|
| Error Prone | javac 外掛，編譯時就報 | ERROR 等級的規則誤判極少，紅了幾乎一定是 bug；不必另外一個步驟，編譯過就是檢查過 |
| SpotBugs | 編譯後掃 bytecode | 跟 Error Prone 重疊大，誤判比較多，要另外維護排除清單 |
| Checkstyle | 掃原始碼的風格 | 格式已經交給 formatter 自動排，再用規則「檢查」風格是重工 |
| NullAway | Error Prone 的外掛 | 要在程式碼上標 `@Nullable`，等於改動大半的檔案，先不做 |

Error Prone 只用預設：WARNING 不當成錯誤（不開 `-Werror`）。現在有 14 個警告（`StringCaseLocaleUsage` 6 個最多），
要變成必過就得先清掉，又是一次動很多檔案的改動，之後另外處理。

**前端：ESLint + Prettier，不是 Biome 或 oxlint**。2026 年 ESLint 10 只剩 flat config（`eslint.config.js`），
設定照 Vue 官方 `create-vue` 產生的那套（`@vue/eslint-config-typescript`、`eslint-plugin-vue`、`@vue/eslint-config-prettier`）。

| | 為什麼（不）選 |
|---|---|
| ESLint + Prettier | Vue 專屬規則（`eslint-plugin-vue`）和要型別資訊的規則（`no-floating-promises`）都只有 ESLint 有；Vue 官方範本的預設 |
| Biome | 一支 Rust 工具同時 lint 和格式化、快很多，但沒有 Vue 專屬規則，這個專案大半是 `.vue` |
| oxlint | 比 ESLint 快幾十倍，但規則少、多半不能自動修；常見用法是放在 ESLint 前面當加速層，這個大小的專案 ESLint 本身只要幾秒，不需要 |

ESLint 只管「會出錯的寫法」，排版規則全部關掉（`skip-formatting`）交給 Prettier：兩個工具不管同一件事，就不會互相打架。
Prettier 的設定（不加分號、單引號、120 字寬）照現有程式碼的寫法定；`endOfLine: auto` 是因為這台機器 `core.autocrlf=true`，
checkout 出來是 CRLF、CI 容器裡是 LF，兩邊都要過。

版本：Spotless 8.10.4、palantir-java-format 2.102.0、Error Prone 2.50.0、ESLint 10.12、Prettier 3.9（2026-10 時的最新版）。
參考：
[ESLint vs Biome vs Oxlint 2026（pkgpulse）](https://www.pkgpulse.com/guides/biome-vs-eslint-vs-oxlint-2026)、
[Biome vs ESLint vs Oxlint 2026（DEV）](https://dev.to/royce_fabbd83cb268312e928/biome-vs-eslint-vs-oxlint-js-linters-2026-408h)、
[Spotless Gradle 外掛](https://plugins.gradle.org/plugin/com.diffplug.spotless)、
[palantir-java-format](https://github.com/palantir/palantir-java-format)、
[gradle-errorprone-plugin](https://github.com/tbroyer/gradle-errorprone-plugin)。

## 刻意不做的

- **GitHub 上跑 AI eval（`./gradlew eval`）**：沒有模型。本機的 pre-push 改到 prompt 或模型相關的檔案時才跑（`RUN_EVAL=1`），GitHub 上只確定它編得過。見 [#8 的「AI 品質回歸（eval）」段落](https://github.com/Anderson-chen/talkcal/issues/8)。
- **k6 壓測**：要真模型，CI 量不出有意義的數字。見 [perf/README.md](../perf/README.md)。
- **GitHub 上的依賴快取**：每次都是新機器、重新下載，比較慢。先求跟本機一致，嫌慢再把 `/cache` 接上 `actions/cache`。
- **自架 runner 讓 GitHub 也跑模型測試**：公開 repo 接自架 runner，別人的 PR 會在這台電腦上執行程式碼；
  要做的話只能接自己的 push，而且要另外做安全設定。目前由本機的 pre-push 補這一塊。
