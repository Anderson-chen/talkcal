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

## 文件地圖

架構圖、ADR、issue 分類依據、腳本地圖、SLO、告警、runbook、演練紀錄等跨資料夾的文件，索引在 [docs/README.md](docs/README.md)。
