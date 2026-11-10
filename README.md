# talkcal

[![CI](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml/badge.svg)](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml)

個人行事曆功能，可透過對話方式來建立行程。

技術：Java 25 · Spring Boot 4.1 · Spring AI · Vue 3 · PostgreSQL 18 · llama.cpp／Bedrock · Grafana（Alloy、Mimir、Loki、Tempo） · k6 · Docker Compose · Terraform（AWS）

## 架構

- 本機、雲端的總覽圖和本機細節：[docs/architecture.md](docs/architecture.md#總覽)

## 目錄

| 資料夾 | 放什麼 |
|---|---|
| [`backend/`](backend/README.md) | Spring Boot app：程式碼、Gradle、Dockerfile |
| [`web/`](web/README.md) | Vue 3 前端：程式碼、pnpm、Dockerfile、nginx 設定 |
| [`deploy/`](deploy/README.md) | 把 app、前端、模型、資料庫跑起來的 Docker Compose |
| [`ops/`](ops/README.md) | 觀測：Alloy → Mimir / Loki / Tempo → Grafana |
| [`perf/`](perf/README.md) | k6 壓測 |
| [`ci/`](ci/README.md) | CI 要檢查什麼、在什麼環境檢查 |
| [`cd/`](cd/README.md) | 部署、退版、版本號 |
| [`infra/`](infra/README.md) | 搬上 AWS 的 Terraform |

## 怎麼跑

需要：Docker（含 NVIDIA GPU 支援）、JDK 25。

> **已知限制**：模型檔目前從寫死的 `C:\llm\models` 複製進容器（`deploy/compose.yaml`），所以只能在 Windows 上照原樣跑。
> 要先把 `Qwen3-8B-Q4_K_M.gguf` 放進那個資料夾。改成可設定的路徑是待辦事項。

```bash
docker compose -f deploy/compose.yaml up -d postgres llm-chat   # 資料庫和模型（第一次要等模型複製、載入）
cd backend && ./gradlew deploy                                  # 跑測試、build 並部署 app 和前端
```

瀏覽器開 <http://localhost:18080>。API 文件（Swagger UI）在 <http://localhost:18090/swagger-ui.html>。

要看觀測：再起 `ops/`（`docker compose --env-file deploy/.env -f ops/compose.yaml up -d`；`--env-file` 帶告警寄信的 SMTP，沒帶 Grafana 會重建成不寄信），Grafana 在 <http://localhost:3000>。

本機開發（不進容器）：

```bash
cd backend && ./gradlew bootRun  # app 在 8090，連 deploy/ 的 PostgreSQL 和模型
pnpm --dir web run dev           # 前端在 5173，/api 轉給 8090（在 repo 根目錄跑）
```

常用腳本（CI、部署、退版、備份還原、EC2 開關機、壓測）都收在根目錄的 [justfile](justfile)，不用記它們放在哪、要帶什麼參數：

```bash
just                       # 列出全部指令和說明
just deploy local web      # 等於 cd/apply.sh local web
```

要裝什麼、Windows 上的 PATH 怎麼設，見 justfile 開頭。VS Code 裡用「Tasks: Run Task」：
要選參數的幾個（部署、退版、還原、壓測）有自己的 task，其餘用「just: 任意指令」（[.vscode/tasks.json](.vscode/tasks.json)）。

## 測試

Gradle 指令都在 `backend/` 裡跑：

```bash
./gradlew test               # 單元測試、web 切片、架構規則：不需要任何外部服務，秒回
./gradlew integrationTest    # 對真的 llama-server 和 PostgreSQL（Testcontainers）；模型沒開就自動跳過那幾支
./gradlew test integrationTest jacocoTestReport   # 加上合併的覆蓋率報告（只找漏測、不設門檻，見 docs/test-coverage.md）
../ci/run.sh                 # 跟 CI 完全一樣：在乾淨的容器裡跑上面全部，再加前端的型別檢查和打包
```

CI 在每次 push、PR 時於 GitHub 上跑，本機則由 pre-push hook 在推之前先跑一次（`git config core.hooksPath .githooks` 啟用）。
兩邊走同一支 `ci/run.sh`、同一個映像檔；GitHub 沒有 GPU，模型測試只在本機會真的跑。設計見 [ci/README.md](ci/README.md)。

## 產品範圍怎麼分類

[上線準備的五段](docs/classification.md)回答「怎麼做到」；產品範圍回答「要做到什麼」：只有行事曆加 AI 助理才會這樣問、要看使用者要什麼才能決定的事。
分成八個面向，每個面向是一個 epic issue（標 `epic` 和 `product: 面向`），裡面列細項的現況；決定要做的才拆成子 issue，決定不做的在 epic 裡寫理由。

| 面向 | 要回答的問題 | 對應的機制（上線準備） | epic |
|---|---|---|---|
| 時間解讀 | 使用者說的時間、看到的時間，在各種情境下都是同一個意思嗎 | #46 時區（儲存） | [#189](https://github.com/Anderson-chen/talkcal/issues/189) |
| 同時操作 | 同時操作時，使用者看到的結果合理嗎 | #50 並行修改、#51 冪等寫入、#187 排程工作只執行一次 | [#190](https://github.com/Anderson-chen/talkcal/issues/190) |
| 行程管理 | 行程從建立、修改、提醒到刪除，使用者需要的每一步都有嗎 | | [#191](https://github.com/Anderson-chen/talkcal/issues/191) |
| 資料互通 | 資料進得來、出得去、能跟別人和其他行事曆共用嗎 | #11 認證與授權 | [#192](https://github.com/Anderson-chen/talkcal/issues/192) |
| 多語系 | 換一種語言、換一個地方的人還能用嗎 | | [#193](https://github.com/Anderson-chen/talkcal/issues/193) |
| AI 助理使用規則 | 使用者能信任它、成本控制得住、答錯時有回饋管道嗎 | #12 濫用防護、#35 成本、#36 產品指標 | [#194](https://github.com/Anderson-chen/talkcal/issues/194) |
| 帳號與個資 | 使用者能完整掌控自己的帳號和資料，也符合法規嗎 | #52 隱私 | [#195](https://github.com/Anderson-chen/talkcal/issues/195) |
| 回應速度 | 使用者覺得快嗎，而不只是後端的數字 | #34 SLO、③ Run 的壓測與容量 | [#196](https://github.com/Anderson-chen/talkcal/issues/196) |

### 面向的依據

八個面向是從「用行事曆加 AI 助理的人會問什麼」整理出來的，再拿 [ISO/IEC 25010:2023](https://quality.arc42.org/articles/iso-25010-update-2023) 的九個產品品質特性對照，確認沒有整塊漏掉：

| ISO 25010 特性 | 對到的面向 |
|---|---|
| Functional suitability（功能完整、正確） | 時間解讀、行程管理 |
| Performance efficiency | 回應速度 |
| Compatibility（互通） | 資料互通 |
| Interaction capability（好不好用、包容性） | 多語系、AI 助理使用規則 |
| Reliability | 同時操作 |
| Security | 帳號與個資 |
| Flexibility（可攜、擴展） | 資料互通 |
| Safety | AI 助理使用規則（答錯時的回饋管道） |
| Maintainability | 沒有：要問工程才能決定，放上線準備的 ⑤ Evolve |

判斷一個新問題放哪個面向：

1. 看它回答的是表格裡哪個「要回答的問題」，一張 issue 只貼一個 `product:` label。
2. 哪個都回答不了，而且已經想得到三件以上的細項，才開新的面向（新的 epic 和 `product:` label，README 表格、label 說明、epic 標題用同一個名字）；不到三件就先放進最接近的面向，免得 epic 底下只有一兩件、只是多一層。

### 跟上線準備怎麼分

兩者是看同一個產品的兩個角度，不是第六段：

| | 上線準備（五段） | 產品範圍（八個面向） |
|---|---|---|
| 角度 | 工程與營運面 | 產品面 |
| 回答 | 怎麼做到：怎麼把它做穩、顧好 | 要做到什麼 |
| 誰決定 | 工程，有業界標準做法 | 產品，看使用者要什麼 |
| 換一個產品 | 大部分照搬 | 幾乎全部重想 |

新的面向放哪邊，只問一句：**這件事要問誰才能決定？** 兩邊都沾到的不選邊，拆成兩項：機制放上線準備、規則放產品範圍，互相連結。
例如時區：「存下來不會被資料庫偷偷換算」是機制，在 #46；「要支援哪些時區、出國時顯示哪裡的時間」是規則，在 #189。
同理，限流（擋濫用）和方案配額（免費一天幾次）、量出成本和依成本定價，都是一邊一項。

這條線不等於「非功能需求」和「功能需求」：「使用者能接受等多久」是效能、算非功能需求，但要問使用者才能決定，所以放產品範圍。

## 文件地圖

| 想知道 | 看哪裡 |
|---|---|
| issue 貼哪個 `stage:` label：上線準備的五段怎麼分、判斷問句、對照的業界框架 | [docs/classification.md](docs/classification.md) |
| 為什麼選 Spring AI、llama.cpp、JdbcClient、Alloy…：每個重大決定的選項和代價 | [docs/adr/](docs/adr/README.md) |
| 整體架構圖：本機、雲端的總覽，本機兩組 compose 怎麼接 | [docs/architecture.md](docs/architecture.md) |
| AWS 階段一互動架構圖：每個元件由哪個檔案哪幾行決定、為什麼這樣選、業界怎麼做 | [https://anderson-chen.github.io/talkcal/](https://anderson-chen.github.io/talkcal/)（原始檔 [docs/architecture/index.html](docs/architecture/index.html)） |
| 搬上 AWS 的兩個階段（EC2、EKS） | [infra/README.md](infra/README.md) |
| 怎麼部署、容器之間怎麼溝通、對照雲端是什麼 | [deploy/README.md](deploy/README.md) |
| 怎麼觀測、三種訊號怎麼互跳 | [ops/README.md](ops/README.md) |
| 怎麼壓測、怎麼讀結果 | [perf/README.md](perf/README.md) |
| 每個請求花多少錢、自架 GPU 和 Bedrock 在哪裡交叉 | [docs/cost.md](docs/cost.md) |
| 每支自動化腳本什麼時候跑、誰叫它、對應哪個 `just` 指令 | [docs/automation.md](docs/automation.md) |
| CI 怎麼跑、為什麼在容器裡跑 | [ci/README.md](ci/README.md) |
| 用到的模型、套件、映像檔能不能這樣用，CI 怎麼擋 GPL／AGPL；本專案為什麼是 MIT | [docs/licenses.md](docs/licenses.md) |
| 套件怎麼自動升級、升級 PR 怎麼審 | [docs/dependency-updates.md](docs/dependency-updates.md) |
| 測試覆蓋率怎麼看、為什麼不設門檻 | [docs/test-coverage.md](docs/test-coverage.md) |
| 後端怎麼分層、ArchUnit 守哪些規則 | [backend/README.md](backend/README.md) |
| 前端怎麼組成 | [web/README.md](web/README.md) |
| 畫面設計稿和實作的差異 | [docs/design/calendar/README.md](docs/design/calendar/README.md) |
| 資料表為什麼長這樣 | [backend/src/main/resources/db/migration](backend/src/main/resources/db/migration)（每支 SQL 開頭都寫了理由） |
