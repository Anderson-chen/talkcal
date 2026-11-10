# ci：持續整合（CI）

每次改動都會在乾淨的容器裡自動檢查一遍。後端跑單元測試、架構規則、整合測試，編譯時 Error Prone 一起跑；前端跑 lint（ESLint）、單元測試（Vitest）、型別檢查、打包和 E2E（Playwright 開真的 Chromium）。

本機（push 之前）和 GitHub（push 之後）跑的是同一支 script、同一個映像檔，所以本機綠燈，GitHub 也會是綠燈。

整條 CI → CD 的流程圖和每一關擋什麼，見 [docs/architecture.md](../docs/architecture.md#cicd從-commit-到線上)。每支腳本（CI、CD，以及備份、EC2）什麼時候跑、誰叫它、對應哪個 `just` 指令，見[腳本地圖](../docs/automation.md)。

## 怎麼跑

```bash
ci/run.sh                               # 手動跑一次，測 HEAD
git config core.hooksPath .githooks     # 啟用 pre-push hook（每個 clone 做一次）
git push --no-verify                    # 確定要跳過檢查時
```

要先開著 Docker Desktop。llama-server 有開的話，模型相關的測試會真的跑；沒開就跳過，摘要會列出跳過了哪些。

第一次跑要 build 映像檔（含 Gradle 本體）、下載依賴，比較慢。之後映像檔有快取，依賴放在 volume `talkcal-ci-gradle`、`talkcal-ci-pnpm`（pnpm store）裡，就快了。

摘要在 `ci/build/summary.md`，失敗時的 HTML 報告在 `ci/build/reports/`。覆蓋率報告在 `ci/build/reports/jacoco/`、`ci/build/web-coverage/`，GitHub 上是 `coverage` artifact，怎麼看見 [docs/test-coverage.md](../docs/test-coverage.md)。

## 架構：檢查內容只寫一次，平台只是入口

```
.github/workflows/ci.yml ─┐
                          ├──▶ ci/run.sh ──▶ 容器（ci/Dockerfile）裡跑 ci/checks.sh
.githooks/pre-push ───────┘     在主機上       在容器裡
```

| 檔案 | 回答的問題 | 換平台時 |
|---|---|---|
| `checks.sh` | 要檢查什麼 | 不動 |
| `Dockerfile` | 在什麼環境檢查（JDK 25、Node 22、Gradle 本體、Playwright 的 Chromium） | 不動 |
| `run.sh` | 怎麼把程式碼放進容器、跑完怎麼拿回結果 | 不動 |
| `.github/workflows/ci.yml` | 什麼時候、在哪台機器跑，結果貼到哪 | 換成 `.gitlab-ci.yml`、`bitbucket-pipelines.yml`、`Jenkinsfile` |

道理跟後端的六角架構一樣：`checks.sh` 是核心，各平台的設定檔是 adapter。如果 `ci.yml` 自己寫一串 `setup-java`、`./gradlew test`，等於把業務規則寫進 adapter；換到 GitLab 要整份重寫，本機的 hook 也得另外抄一份，兩邊遲早會不一樣。

## 為什麼在容器裡跑，而不是直接用主機

直接在主機跑會遇到這些事：

| 問題 | 例子 | 容器怎麼解決 |
|---|---|---|
| 測到的不是要推的東西 | 忘了 `git add` 的檔案讓本機測試過了，推上去才紅 | 用 `git archive` 只複製那個 commit 進去 |
| 環境不一樣 | 主機預設是別版的 JDK、Node，或有個人的 Gradle 設定 | JDK、Node 版本只定義在 `Dockerfile` |
| 殘留狀態 | 舊的 `build/`、`node_modules` 讓結果看起來沒事 | 每次都是新容器，跑完就刪 |

代價和處理方式：

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

兩邊各守一道。本機補上 GitHub 跑不到的模型測試，GitHub 補上本機可以用 `--no-verify` 繞過的那一道。

## 祕密掃描

`ci/secrets.sh` 用 gitleaks 掃 git 歷史，不只掃最新的檔案。key 在一個 commit 加進去、下一個 commit 刪掉，檔案看起來乾淨，但推上去之後任何人 clone 都拿得到。所以它不放在 `checks.sh` 裡：那邊只有某一個 commit 的檔案，沒有 `.git`。

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

CI 只掃 `HEAD` 走得到的 commit，不掃其他分支。`fetch-depth: 0` 會把所有分支一起抓下來，而 gitleaks 沒指定範圍時會用 `git log --all`，結果別的 PR 分支上一個誤判，所有 PR 的 `secrets` 都紅燈（#180）。在 PR 上，`HEAD` 是 master 加這個 PR 的 merge commit，所以 master 的歷史和這個 PR 的每個 commit 都會掃到；別的分支由它自己的 PR 負責。

pre-push 先掃祕密再跑測試。掃描只要幾秒，而且測試紅了可以再推一次修，key 送出去就收不回來了。

### GitHub 已經會擋，為什麼還要 gitleaks

這個 repo 用到的 key（AWS、OpenAI 這類）GitHub 都認得，公開 repo 的 push protection 和背景掃描已經擋住大部分的情況。gitleaks 補的是這幾塊：

- 在本機就擋下。GitHub 擋的時候 key 已經送出這台電腦，pre-push 擋的時候還沒。
- 通用規則。自己系統發的 token、私鑰檔，GitHub 不認得。
- 放行要留下紀錄。GitHub 被擋時在網頁上填個理由就能放行；這裡的 CI 是必過檢查，跳不過。
- 不綁平台。私人 repo 要付費才有 GitHub 這套，換到 GitLab 或公司內部的 Git 也照樣有效。

代價是 pre-push 多約 1 秒、CI 多一個並行的 job，所以留著。

被誤判時（例如測試用的假 key），在那一行加註解 `gitleaks:allow`，或把報告裡的 Fingerprint 寫進 `.gitleaksignore`。

## 漏洞掃描

`ci/vulns.sh` 用 Trivy 掃三種東西：

- 設定（`--repo`，`trivy config`）：見下面的「設定檢查」。
- 前端的 lockfile（`--repo`，`trivy fs`）：`web/pnpm-lock.yaml`。前端映像檔裡只剩 nginx 和打包好的靜態檔，看不出用了哪個套件的哪一版，所以掃映像檔抓不到（#235）。這裡包含 devDependencies（`--include-dev-deps`）：前端套件幾乎都是 dev（vite、vitest…），它們不上線，但會在開發者的電腦和 CI 上執行。
- 映像檔（其餘參數，`trivy image`）：基底映像檔的作業系統套件和 jar 裡的 Java 函式庫一次掃到。後端不另外掃 `build.gradle`，jar 裡就是解析完的確切版本。

有 HIGH、CRITICAL 而且已經有修補版本的，就 exit 1。上游還沒修的我們也沒得升，擋了也沒事可做。

```bash
docker compose -f deploy/compose.yaml build app web
ci/vulns.sh --repo talkcal/app:local talkcal/web:local $(ci/compose-images.sh)
```

| 時機 | 為什麼 |
|---|---|
| 每個 PR、push 到 master | 改動帶進新的套件版本、換了基底映像檔 |
| 每天台灣 09:00（排程，只跑這個 job） | 程式碼沒變，但新的 CVE 公布了。失敗時 GitHub 寄信 |
| 手動（Actions 頁面的 Run workflow） | 大漏洞剛公布，想馬上知道有沒有中 |

### 掃哪些映像檔

`vulns` job 會擋自己 build 的 `talkcal/app`、`talkcal/web`，加上 `ci/compose-images.sh` 列出的第三方映像檔。PR 上只有動到 compose、`ci/trivyignore.yaml`、`ci/compose-images.sh`、`ci/vulns.sh`、`ci/Dockerfile` 時才掃全部，其他 PR 只掃 postgres；排程和 push 到 master 一律全掃。

第三方的清單由 `ci/compose-images.sh` 問 compose（`docker compose config --images`，疊的檔案跟 `deploy.sh` 一樣），不在 ci.yml 另外抄一份。用 compose 自己解析而不是 grep，因為疊上去的檔案會蓋掉 image，有 profiles 的服務（mailpit）雲端也不會起，grep 都看不出來。

第三方第一次掃（2026-10-30）有約 400 筆 HIGH、CRITICAL，#239 升版之後才改成擋，跟 #85「先掃、修完再擋」是同一個做法。升不掉的（上游還沒帶修補、版本未滿 14 天）寫在 `ci/trivyignore.yaml`，附理由和到期日。新公布的 CVE 或到期的例外會讓每天的排程和 master 變紅，這時要升版，或評估後寫例外。

不掃的映像檔和理由：

| 映像檔 | 理由 |
|---|---|
| `talkcal/llama-server` | 只有 `model_backend = gpu` 才用（預設是 Bedrock）；要用 CUDA devel 映像檔編 llama.cpp，成品 4 GB 多，每個 PR 在 GitHub 的機器上 build 不划算。切回 gpu 時要補 |
| `mailpit`（`ops/compose.yaml`） | `profiles: ["mailtest"]`，只在本機測試寄信時起 |
| `toxiproxy`（`deploy/compose.chaos.yaml`） | 只在本機故障演練時疊上去，不上線 |
| `python`（`ci/alerting/compose.yaml`） | 只在 CI 的告警測試裡跑假 app，已經釘 digest |
| Trivy、gitleaks、ShellCheck、CodeQL、promtool 這類工具 | 只在 CI 跑完就丟，不處理外部輸入；版本＋digest 釘死，升級交給 Renovate |

掃到漏洞時，有修補版本就升：基底映像檔重新 build，函式庫在 build.gradle 拉高版本。真的修不了、評估過碰不到的，寫進 `ci/trivyignore.yaml`，**一定要附理由和到期日**。到期後它會重新被報出來，逼人再看一次。

不用 trivy-action。Trivy 在 2026-03 被入侵過，v0.69.4 和 trivy-action 幾乎所有 tag 都被換成會偷 CI secret 的版本。這裡跟 gitleaks 一樣直接跑映像檔，版本和 digest 都釘死，用的是事件之後的版本。

### 設定檢查：Dockerfile、compose、Terraform

`ci/vulns.sh --repo` 的第一步是 `trivy config`（#235），用 Trivy 內建的規則抓 Dockerfile 用 root 跑、Terraform 的 S3 沒加密、security group 全開這類寫法。Trivy 沒有 compose 的規則（官方支援表上是「-」），所以自己寫在 [`ci/config-checks/`](config-checks/)，用 Rego，一條規則一個檔：

| 規則 | 擋什麼 |
|---|---|
| `TALKCAL-COMPOSE-001` | `privileged`、`network_mode`／`pid`／`ipc: host` |
| `TALKCAL-COMPOSE-002` | 掛 `docker.sock`（`:ro` 也擋：只擋寫檔，擋不住 API 呼叫） |
| `TALKCAL-COMPOSE-003` | `user: root`／`0` |
| `TALKCAL-COMPOSE-004` | 映像檔沒寫 tag 或寫 `latest` |
| `TALKCAL-COMPOSE-005` | `PASSWORD`、`SECRET`、`TOKEN` 這類變數直接寫值，不是 `${變數}` |

剛加進來時抓到 10 筆，都是有意的設計，寫進 `ci/trivyignore.yaml` 的 `misconfigurations`，限定檔案、附理由和到期日，例如 CI 容器和 model-seed 用 root、Alloy 掛 docker.sock、S3 用 SSE-S3 不用 KMS、EC2 不經 NAT 直接用公開 IP。其中只有 `web/Dockerfile` 的 nginx 用 root 該改，但要換埠，會牽動 Caddy 和 compose，所以另外做，到期日給得比較短。

選 `trivy config`，不用 hadolint：

| | 授權 | 為什麼（不）選 |
|---|---|---|
| `trivy config` | Apache-2.0 | 已經釘了版本＋digest、例外跟漏洞共用一份檔；Dockerfile 之外還掃 Terraform；能載入自己寫的 Rego 規則，compose 也收進同一支 |
| hadolint | GPL-3.0 | 只管 Dockerfile，偏寫法（`apt-get` 沒清快取、`ADD` 換 `COPY`），不管用 root 跑；compose、Terraform 都不掃 |
| KICS | Apache-2.0 | 內建 compose、Dockerfile、Terraform 的規則，最完整；但又是一支要釘版本的工具、另一份例外清單，映像檔 2026-10-21 才重 build（不滿兩週） |
| compose-lint | MIT | 專門掃 compose 的安全問題，規則跟上面那張表很像；2026-04 才建立、63★，太新 |

反例（2026-10-30）：

| 造出來的設定 | 結果 |
|---|---|
| 現在的設定（10 筆都有例外） | 通過 |
| 拿掉例外 | 紅：上面那 10 筆 |
| 一份 compose 裡 `privileged`、`network_mode: host`、長寫的 docker.sock、`user: "0:0"`、`image: nginx`、`:latest`、map 和 list 兩種寫法的寫死密碼 | 紅：8 筆全抓到 |
| 同一份裡 `registry.example.com:5000/app:1.2.3`、`${DB_PASSWORD}` | 不報 |

### 前端套件的選型（2026-10 查的）

用 `trivy fs`，不另外加 `pnpm audit` 或 OSV-Scanner：

| | 授權 | 為什麼（不）選 |
|---|---|---|
| `trivy fs` | Apache-2.0 | 映像檔已經釘了版本＋digest、漏洞資料庫已經有快取，多掃一個檔案幾秒；例外跟映像檔共用 `ci/trivyignore.yaml`（一樣要理由和到期日）；每天的排程一起掃到。認得 pnpm、npm、yarn 的 lockfile |
| `pnpm audit --audit-level high` | （pnpm 內建） | 不用多裝東西，但只問 npm registry 的 advisory、例外要另外寫在 `pnpm-workspace.yaml`，跟 Trivy 的例外分兩處維護；也只認 pnpm |
| OSV-Scanner | Apache-2.0 | Google 維護、資料來源是 OSV（GitHub advisory 也匯進去了），能力跟 `trivy fs` 重疊，多一個要釘版本的工具、多一份例外清單 |

反例（2026-10-30）：

| lockfile | 結果 |
|---|---|
| master 原本的 vite 8.3.2 | 紅：`CVE-2026-112942`（就是 GHSA-587h-7wmj-h5pm，HIGH）；後面的映像檔照樣掃完 |
| 同上，拿掉 `--include-dev-deps` | 綠（漏掉）：vite 是 devDependency，所以這個旗標不能拿掉 |
| vite 8.3.4（這次升上去的） | 通過 |

## 原始碼安全分析（SAST）

`ci/vulns.sh` 找別人的套件已經公布的漏洞，`ci/sast.sh` 找自己寫的程式碼裡的安全問題（#235）：使用者輸入一路流到 SQL、HTML、shell 指令這類地方（taint analysis），還有 workflow 裡 `${{ }}` 直接貼進 `run` 造成的注入。用 CodeQL，在容器裡跑（`ci/sast/Dockerfile`），本機和 GitHub 跑同一支。

```bash
ci/sast.sh             # 掃 HEAD，約 2～4 分鐘；第一次要下載 CodeQL bundle（約 700 MB）
```

| 掃什麼 | 怎麼掃 |
|---|---|
| 後端 Java | `build-mode none`：不編譯，但 CodeQL 會跑 `./gradlew` 拿依賴清單（所以映像檔的底是 JDK 25），外部函式庫的型別才認得出來 |
| 前端 TS、`.vue` | `<script>` 的資料流；模板裡的 `v-html` CodeQL 追不到，由 ESLint 的 `vue/no-v-html` 擋（見下面反例） |
| `.github/workflows/` | 注入、權限給太大 |

查詢用各語言的預設組（code-scanning），跟 GitHub default setup 同一組，只收誤判少的。結果全部列在 `ci/build/sast/summary.md`，GitHub 上會貼到結果頁；security-severity 7.0 以上（HIGH、CRITICAL）才紅燈，標準跟 `vulns.sh` 一樣。

CodeQL 不認得的 API 要自己補。[`ci/sast/models/`](sast/models/) 用 Models as Data 告訴它 `JdbcClient.sql(String)` 是 SQL 的 sink：CodeQL 2.27 只認得 `JdbcTemplate`，而這個專案的 SQL 全部走 Spring 6.1 的 `JdbcClient`，不補的話拼字串也是綠燈。之後加別的資料庫、HTTP client、模板引擎時，先造一個反例確認 CodeQL 認得，不認得就在這裡補。

排除的項目寫在 [`ci/sast/codeql-config.yml`](sast/codeql-config.yml)，每條附理由。誤判時先試著改寫法讓它看得懂（例如補跳脫），真的不行再排除。

### 選型（2026-10 查的）

用 CodeQL CLI（在 `ci/` 裡跑），不用 CodeQL default setup、Semgrep／Opengrep、eslint-plugin-security：

| | 授權 | 為什麼（不）選 |
|---|---|---|
| CodeQL CLI 2.27.2 | CLI：GitHub CodeQL Terms（OSI 授權的開源專案免費）；查詢：MIT | 跨檔案、跨函式的 taint analysis，Java、JS/TS、Actions 一支工具涵蓋；跟 GitHub default setup 同一個引擎、同一組查詢，但寫在 `ci/`，本機跑得到 |
| CodeQL default setup | 同上 | 不用寫任何設定，但只在 GitHub 上跑，本機重現不了；開關和設定在 repo 的 Settings 裡，不在程式碼裡，PR 看不出改了什麼 |
| Semgrep CE | 引擎 LGPL-2.1；官方規則 Semgrep Rules License（非 OSI，限制商用） | 規則好寫、快；但 2024-12 起跨檔案的 taint 只在付費版，CE 只看單一函式 |
| Opengrep | LGPL-2.1 | Semgrep CE 的社群分支（2025），補回跨檔案分析；還年輕，規則要從 Semgrep 的拿，授權不清楚 |
| eslint-plugin-security | Apache-2.0 | 規則幾乎都是給 Node.js 伺服器的（`child_process`、`fs` 路徑），這是瀏覽器裡的 Vue；只比對語法、誤判多（`detect-object-injection`） |

授權上這是例外：下面 lint 的選型只收 MIT、Apache-2.0，CodeQL CLI 不是。收它是因為它不會打包進任何產物，而且 GitHub 的條款明寫 OSI 授權的開源專案可以免費用在 CI，所以這道檢查的前提是專案採用 MIT（#53）。專案如果改成不開源，就要換掉它，或改用 GitHub Advanced Security 的付費授權。

前端另外開了 eslint-plugin-vue 的 `vue/no-v-html`（已經裝了，Vue 官方）。CodeQL 追不到模板裡的 `v-html`，而這個專案的內容幾乎都來自使用者或 AI（行程標題、助理的回答），所以 `v-html` 一律擋，比追資料從哪來更保險。

反例（2026-10-30，在暫時的 commit 上做，沒有推上去）：

| 造出來的程式碼 | 結果 |
|---|---|
| 現在的程式碼 | 綠，0 個結果（`docs/design/` 的原型排除後；架構頁的一個連結補了跳脫，見下） |
| `@RequestParam` 直接拼進 `jdbc.sql(...)`（`JdbcClient`） | 紅：`java/sql-injection` 8.8 |
| 同上，但沒有 `ci/sast/models/` 或映像檔沒有 JDK（拿不到依賴） | **綠（漏掉）**：所以這兩樣都不能拿掉 |
| `@RequestParam` 拼進 `JdbcTemplate.queryForList(...)` | 紅：`java/sql-injection` 8.8 |
| `location.search` 的值設給 `innerHTML`（`.ts`） | 紅：`js/xss` 7.8 |
| `location.search` 的值放進 `v-html`（`.vue`） | CodeQL 綠（漏掉）；ESLint 紅：`vue/no-v-html` |

第一次跑時，CodeQL 在會發佈到 GitHub Pages 的 `docs/architecture/index.html` 報了 `js/xss-through-dom`（7.8）：檔案連結的 `href` 沒有跳脫。實際上那個值只來自頁面自己的下拉選單，打不進來，但補一個 `esc()` 比寫例外乾淨，所以補了。

## lint

讓常見的錯在測試之前就紅燈（#22）。只抓會出錯的寫法，不管格式。

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

挑工具時看三件事：

- 授權：只用 MIT、Apache-2.0。這些都是開發工具，不會打包進 jar 或映像檔。
- 社群熱門度：維護者、星數、下載量，最後一次發版多久以前。
- 版本發布滿兩週：新版被植入惡意程式碼，通常幾天內就會被發現，例如 Trivy 2026-03 的事件（見上面的「漏洞掃描」）。前端套件的兩週規則寫在 `web/pnpm-workspace.yaml`（`minimumReleaseAge`），`pnpm add` 會自動套用，連間接依賴也算；`checks.sh` 的 `pnpm install --frozen-lockfile` 也照它檢查 lockfile，不滿兩週的版本進不了 master。

後端用 Error Prone，不加 SpotBugs、Checkstyle、PMD：

| | 授權 | 熱門度 | 為什麼（不）選 |
|---|---|---|---|
| Error Prone 2.50.0 | Apache-2.0 | 7.3k★，Google 維護 | javac 外掛，編譯過就是檢查過；ERROR 等級的規則誤判極少，紅了幾乎一定是 bug |
| SpotBugs | LGPL-2.1 | 4.0k★ | 編譯後掃 bytecode，跟 Error Prone 重疊大，誤判比較多，要另外維護排除清單 |
| Checkstyle | LGPL-2.1 | 9.6k★ | 檢查的是風格，那是 formatter 的事（#179） |
| NullAway | MIT | 4.1k★ | Error Prone 的外掛，要在程式碼上標 `@Nullable`，等於改動大半的檔案，先不做 |

Error Prone 只用預設：WARNING 不當成錯誤，沒開 `-Werror`。現在有 14 個警告，最多的是 `StringCaseLocaleUsage` 的 6 個；要變成必過得先清掉它們，會動到不少 Java 檔，之後另外處理。Gradle 外掛用 [net.ltgt.errorprone](https://github.com/tbroyer/gradle-errorprone-plugin)（Apache-2.0），這是 Error Prone 官方文件指定的 Gradle 接法。

前端用 ESLint，不用 Biome 或 oxlint。2026 年的 ESLint 10 只剩 flat config（`eslint.config.js`），設定照 Vue 官方 `create-vue` 產生的那套。

| | 授權 | 熱門度（npm 週下載） | 為什麼（不）選 |
|---|---|---|---|
| ESLint 10 | MIT | 27.6k★，9600 萬 | Vue 專屬規則（eslint-plugin-vue，Vue 官方）和要型別資訊的規則（`no-floating-promises`）都只有 ESLint 有 |
| Biome | Apache-2.0 | 26.0k★，1100 萬 | 一支 Rust 工具同時 lint 和格式化、快很多，但沒有 Vue 專屬規則，這個專案大半是 `.vue` |
| oxlint | MIT | 23.1k★，1600 萬 | 比 ESLint 快幾十倍，但規則少、多半不能自動修；常見用法是放在 ESLint 前面當加速層，這個大小的專案 ESLint 本身只要幾秒 |

外掛有 eslint-plugin-vue（MIT，Vue 官方，370 萬）、@vue/eslint-config-typescript（MIT，Vue 官方，90 萬）、eslint-plugin-playwright（MIT，397★，但每週 300 萬下載，用 Playwright 寫 E2E 的專案幾乎都會裝）。

### shell 腳本：ShellCheck

ShellCheck 是 `checks.sh` 的第一步，掃 repo 裡所有 `.sh` 和 `.githooks/`（#235）。這個 repo 的 CI、部署、備份都是 shell 腳本，沒加引號被拆成好幾個參數、`cd` 失敗還繼續往下跑這類錯，要等執行到那一行才會發現，常常是在 EC2 上部署到一半。

ShellCheck 是 GPL-3.0，算授權規則的例外：它只在 CI 容器裡執行、不打包進任何產物，而 shell 腳本的靜態檢查沒有其他像樣的選擇，2026 年它仍是事實標準。執行檔從官方映像檔 `koalaman/shellcheck:v0.11.0` 搬進 `ci/Dockerfile`，版本＋digest 釘死；不用 apt 裝，因為 Ubuntu 24.04 的是 2022 年的 0.9.0。

所有等級都擋，包括 style。剛加進來時有 22 個結果：改得了的就改（`cd` 沒檢查、`$PROJECT` 沒加引號、`echo "$(cmd)"`、用不到的變數）；有意這樣寫的，在那一行上面寫 `# shellcheck disable=SCxxxx` 並附理由（`trap` 要立刻展開、`node -e` 單引號裡的 JS 模板字串…）。

不掃 `infra/envs/1-ec2/user-data.sh.tftpl`。那是 Terraform 的樣板，`${}` 有一半是 Terraform 先代換的，ShellCheck 看不懂。

反例：

| 造出來的腳本 | 結果 |
|---|---|
| 現在的 24 支 | 通過 |
| `cd "$dir"` 沒檢查、`rm -rf $dir/build` 沒加引號 | 紅：`SC2164`、`SC2086` |

### 格式化為什麼還沒做

格式化（Spotless + google-java-format、Prettier）另外放在 [#179](https://github.com/Anderson-chen/talkcal/issues/179)。一次把整個 codebase 排好才划算，但 2026-10 時有 30 個開著的 PR，現在排會全面衝突。只排改過的檔案（Spotless 的 ratchet）也試過，結果之後每個碰到舊檔的 PR 都要把整份檔案重排，邏輯和格式混在同一個 commit，所以沒採用。等開著的 PR 消化完再一次做。

參考資料：[ESLint vs Biome vs Oxlint 2026（pkgpulse）](https://www.pkgpulse.com/guides/biome-vs-eslint-vs-oxlint-2026)、[Biome vs ESLint vs Oxlint 2026（DEV）](https://dev.to/royce_fabbd83cb268312e928/biome-vs-eslint-vs-oxlint-js-linters-2026-408h)、[gradle-errorprone-plugin](https://github.com/tbroyer/gradle-errorprone-plugin)。星數、下載量是 2026-10-20 的數字。

## 授權檢查

會打包出去的依賴不能是 GPL、AGPL、LGPL，也不能是認不出來的授權。這在 `checks.sh` 裡跑，本機 pre-push 也會跑到：

| 範圍 | 怎麼查 | 規則 |
|---|---|---|
| 後端 `runtimeClasspath` | Gradle 的 `checkLicense`（`./gradlew check` 也會跑） | 允許清單 `backend/allowed-licenses.json` |
| 前端 `dependencies` | `ci/licenses.sh`：另外裝一份只有 `dependencies` 的 node_modules，用 Trivy 讀 | Trivy 的分類，擋 CRITICAL、HIGH、UNKNOWN，外加「沒寫授權」「一個都沒掃到」 |

兩邊用不同工具，是因為 Trivy 讀不到 jar 的授權，也讀不到 pnpm-lock.yaml 的授權，讀不到時會直接通過、不報錯。詳細理由、被擋了怎麼辦、反例驗證，都在 [docs/licenses.md](../docs/licenses.md)。

## DB migration 檢查

`ci/migrations.sh` 跟目標分支比，看這次動了哪些 migration（#18）：

```bash
ci/migrations.sh               # 跟 origin/master 比
ci/migrations.sh <commit>      # 跟指定的 commit 比
```

| 檢查 | 擋什麼 | 為什麼 |
|---|---|---|
| 已經在目標分支裡的 migration 不能改、刪、改名 | 偷改 V11、把 V10 改名 | Flyway 比對 checksum，已經跑過的資料庫一啟動就驗證失敗，部署到一半才發現 |
| 新增的 migration 過 [squawk](https://squawkhq.com/docs/rules)（設定在 `ci/squawk.toml`） | DROP／RENAME COLUMN、改型別、加 NOT NULL 沒預設值、沒 CONCURRENTLY 的索引、ADD CONSTRAINT 沒 NOT VALID、沒設 lock_timeout／statement_timeout | 新舊版本同時在線時舊版會壞，或整張表被鎖住。每條規則的理由和安全的替代做法見 [squawk 規則清單](https://squawkhq.com/docs/rules) |

只看新增的檔案。V1～V11 已經跑過、改不了，掃出來的警告（例如 V2 的 `prefer-timestamp-tz`）也修不掉。真的要做危險的變更，例如 contract 階段刪掉沒人用的欄位，就在那一句上面加 `-- squawk-ignore <規則>` 並寫理由，PR 的 diff 上看得到。

用 squawk 而不是自己 grep：grep `DROP COLUMN` 只抓得到字面，抓不到「同一個交易裡 ADD CONSTRAINT NOT VALID 又 VALIDATE，鎖一路拿到交易結束」這種要懂鎖的問題。squawk 用 PostgreSQL 自己的 parser 解析 SQL，規則照 PostgreSQL 各版本的鎖行為（`pg_version` 設成跟 compose 一樣）。用 npm 的 `squawk-cli`，版本釘死；執行檔是同版本的 npm 平台套件，安裝時不另外下載。

反例（2026-10-16，在暫時的分支上做，沒有推上去）：

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

還沒做：把 `migrations` 加進 ruleset 的必過檢查，這要到 GitHub 的設定裡手動加。

## SLO 錄製規則檢查

`ci/rules.sh` 用 promtool 檢查 [ops/mimir/rules/](../ops/mimir/rules/) 的錄製規則，再跑單元測試 [ops/mimir/slo.test.yaml](../ops/mimir/slo.test.yaml)：餵假的請求序列，比對錄出來的 SLI 和 burn rate。GitHub 上它是獨立的一個 job（`rules`）。

```bash
ci/rules.sh
```

| 造出來的錯 | 結果 |
|------------|------|
| 比例的分母拿掉 `> 0` | 紅：沒流量的那段錄成 NaN，測試期望「沒資料」 |
| 規則不變 | 通過 |

還沒做：把 `rules` 加進 ruleset 的必過檢查（要手動加），pre-push 也還沒接這支。

## 告警端到端測試

`ci/rules.sh` 只驗得到 Mimir 錄製規則的算式。Grafana 的告警規則（門檻、`for`、`noDataState`）、contact point、通知政策、SMTP，要真的跑起來才知道對不對。

`ci/alerting.sh` 起 [ops/compose.yaml](../ops/compose.yaml) 的整組觀測加上 Mailpit（假信箱），再疊上 [ci/alerting/compose.yaml](alerting/compose.yaml)：換專案名稱、拿掉固定的容器名稱和主機埠、讓 Alloy 接一個只有假 app 的網路，所以本機正在跑的那組不受影響。假 app（[ci/alerting/fake-app.py](alerting/fake-app.py)）只吐 `/actuator/prometheus`，可以切成全部回 5xx 或停掉。真的 app 不搬進來：要 build 映像檔，AI 助理還要模型，而這裡要驗的是「指標 → Mimir → Grafana → 寄信」這條鏈，不是 app。

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

每一步的時限寫在 `ci/alerting.sh` 的 `wait_until`，抓的是實測的兩倍。

GitHub 上它是另一支 workflow（`.github/workflows/alerting.yml`），只在 PR 改到告警相關的路徑時跑，也可以手動按。不放進 `ci.yml`，因為 GitHub 的 `paths` 只能設在整支 workflow 上，而這支要跑 15 分鐘，不值得每個 PR 都等。也因為不是每個 PR 都會出現，它不能設成 ruleset 的必過檢查。

| 造出來的錯 | 結果 |
|------------|------|
| `rules.yaml` 的 app-down 門檻 `lt 1` 改成 `lt 0` | 紅：停掉假 app 5 分鐘內沒收到「app 掛了」 |
| 規則不變 | 通過 |
| PR 只改了這些路徑以外的檔案 | 不跑 |

沒有驗的：慢燒（視窗要好幾小時，算式交給 `ci/rules.sh`）、真的信箱收不收得到、會不會進垃圾郵件（CI 裡沒有真的信箱）。

## 刻意不做的

- GitHub 上跑 AI eval（`./gradlew eval`）：沒有模型。改到 prompt 或模型相關的檔案時，本機的 pre-push 才跑（`RUN_EVAL=1`）；GitHub 上只確定它編得過。見 [#8 的「AI 品質回歸（eval）」段落](https://github.com/Anderson-chen/talkcal/issues/8)。
- k6 壓測：要真的模型，CI 量不出有意義的數字。見 [perf/README.md](../perf/README.md)。
- GitHub 上的依賴快取：現在每次都是新機器、重新下載，比較慢。先求跟本機一致，嫌慢再把 `/cache` 接上 `actions/cache`。
- 自架 runner 讓 GitHub 也跑模型測試：公開 repo 接自架 runner，別人的 PR 會在這台電腦上執行程式碼。要做的話只能接自己的 push，還要另外做安全設定。目前這塊由本機的 pre-push 補。
