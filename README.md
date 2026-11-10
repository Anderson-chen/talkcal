# talkcal

[![CI](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml/badge.svg)](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml)

行事曆系統，可透過對話方式來建立行程。 [主架構圖](docs/architecture.md#總覽)


## 技術

| 類別 | 技術 |
|---|---|
| 後端 | Java 25、Spring Boot 4.1 |
| 模型 | 本機：llama.cpp + Qwen3-8B；雲端：Bedrock 的 Qwen3 32B |
| 資料庫 | PostgreSQL 18 + Flyway |
| 前端 | Vue 3 + TypeScript + Vite |
| 觀測 | Grafana Alloy → Mimir、Loki、Tempo → Grafana |
| 壓測 | k6 |
| 部署 | Docker Compose；雲端用 Terraform 開 AWS EC2 |

## 專案結構

| 資料夾 | 說明 |
|---|---|
| [`backend/`](backend/README.md) | Spring Boot app：程式碼、Gradle、Dockerfile |
| [`web/`](web/README.md) | Vue 3 前端：程式碼、pnpm、Dockerfile、nginx 設定 |
| [`deploy/`](deploy/README.md) | 把 app、前端、模型、資料庫跑起來的 Docker Compose |
| [`ops/`](ops/README.md) | 觀測：Alloy → Mimir / Loki / Tempo → Grafana |
| [`perf/`](perf/README.md) | k6 壓測 |
| [`ci/`](ci/README.md) | CI 要檢查什麼、在什麼環境檢查 |
| [`cd/`](cd/README.md) | 部署、退版、版本號 |
| [`infra/`](infra/README.md) | 搬上 AWS 的 Terraform |
| [`docs/`](docs/README.md) | 跨資料夾的文件：架構圖、ADR、issue 分類依據、腳本地圖、測試、SLO、告警、runbook、演練紀錄 |


## 快速開始

需要 Docker（要能用 NVIDIA GPU）和 JDK 25。模型檔目前寫死從 `C:\llm\models` 複製（`deploy/compose.yaml`），所以只能在 Windows 上跑，先把 `Qwen3-8B-Q4_K_M.gguf` 放進去。改成可設定的路徑還沒做。

```bash
docker compose -f deploy/compose.yaml up -d postgres llm-chat   # 第一次要等模型複製、載入
cd backend && ./gradlew deploy                                  # 跑測試，build 並部署 app 和前端
```

開 <http://localhost:18080>。Swagger UI 在 <http://localhost:18090/swagger-ui.html>。

不進容器、直接開發：

```bash
cd backend && ./gradlew bootRun  # app 在 8090，連 deploy/ 的 PostgreSQL 和模型
pnpm --dir web run dev           # 前端在 5173，/api 轉給 8090；在 repo 根目錄跑
```

常用的腳本都可以用 [justfile](justfile) 叫，`just` 會列出全部，例如 `just deploy local web` 等於 `cd/apply.sh local web`。要裝什麼寫在 justfile 開頭；VS Code 裡也能從「Tasks: Run Task」選（[.vscode/tasks.json](.vscode/tasks.json)）。

## 測試

前後端的單元測試、後端整合測試、前端 E2E 可以一次跑完，跟 GitHub 上一樣在乾淨的容器裡跑（在 repo 根目錄）：

```bash
ci/run.sh
```

push 之前 pre-push hook 會先掃祕密（`ci/secrets.sh`）、再跑 `ci/run.sh`（`git config core.hooksPath .githooks` 開啟）。CI 另外還有 lint、祕密和漏洞掃描、SLO 錄製規則的檢查，細節見 [ci/README.md](ci/README.md)。

有哪些測試、各測什麼、怎麼單獨跑（包括 AI eval、壓力測試、雲端的冒煙測試），見 [docs/testing.md](docs/testing.md)。
