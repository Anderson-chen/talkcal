# talkcal

[![CI](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml/badge.svg)](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml)

行事曆系統，可透過對話方式來建立行程。[主架構圖](docs/architecture.md#總覽)

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
| [backend](backend/README.md) | Spring Boot app：程式碼、Gradle、Dockerfile |
| [web](web/README.md) | Vue 3 前端：程式碼、pnpm、Dockerfile、nginx 設定 |
| [deploy](deploy/README.md) | 把 app、前端、模型、資料庫跑起來的 Docker Compose |
| [ops](ops/README.md) | 觀測：Alloy → Mimir / Loki / Tempo → Grafana |
| [perf](perf/README.md) | k6 壓測 |
| [ci](ci/README.md) | CI 要檢查什麼、在什麼環境檢查 |
| [cd](cd/README.md) | 部署、退版、版本號 |
| [infra](infra/README.md) | 搬上 AWS 的 Terraform |
| [docs](docs/README.md) | 跨資料夾的文件：架構圖、ADR、issue 分類依據、腳本地圖、測試、SLO、告警、runbook、演練紀錄 |

## 快速開始

| 環境需求 | 條件 |
|---|---|
| Windows | 模型檔路徑寫死 `C:\llm\models`，改成可設定的路徑還沒做 |
| NVIDIA 顯卡 | RTX 40 系列、驅動 580 以上；比 RTX 40 舊的顯卡要改 `deploy/llama-server/Dockerfile` 的 `CUDA_ARCH` |
| Docker Desktop | WSL 2 後端 |
| JDK | 25 |
| Git Bash | 下面的指令都在這裡、repo 根目錄打 |
| Node.js | 22，只有不進容器開發、單獨跑前端測試時需要 |
| just | 選用，`just` 列出常用腳本：`winget install Casey.Just` |

1. 下載模型（約 5 GB，來源 [Qwen/Qwen3-8B-GGUF](https://huggingface.co/Qwen/Qwen3-8B-GGUF)）：

   ```bash
   curl -L --create-dirs -o C:/llm/models/Qwen3-8B-Q4_K_M.gguf https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf
   ```

2. 寫校驗檔，模型複製進容器時照它驗內容（已經有這個檔案的話，改成手動加這一行，不然會整份蓋掉）：

   ```bash
   echo "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785  Qwen3-8B-Q4_K_M.gguf" > C:/llm/models/SHA256SUMS
   ```

3. 建 `deploy/.env`，打開來填 `DB_PASSWORD`、`ARCHIVE_ACCESS_KEY`、`ARCHIVE_SECRET_KEY`（secret 至少 8 個字）：

   ```bash
   cp deploy/.env.example deploy/.env
   ```

4. 起資料庫、模型和歸檔用的 RustFS（第一次要 build 映像檔，約 10 分鐘）：

   ```bash
   docker compose -f deploy/compose.yaml up -d postgres llm-chat archive archive-init
   ```

   `llm-chat` 起不來時，確認容器拿得到顯卡，要印出 `GPU 0: NVIDIA ...`：

   ```bash
   docker compose -f deploy/compose.yaml run --rm --no-deps --entrypoint nvidia-smi llm-chat -L
   ```

5. 跑測試，部署 app 和前端：

   ```bash
   backend/gradlew -p backend deploy
   ```

6. 開 <http://localhost:18080>。Swagger UI 在 <http://localhost:18090/swagger-ui.html>。

不進容器、直接開發，各開一個終端機（從 repo 根目錄開始）。後端在 8090：

```bash
backend/gradlew -p backend bootRun
```

前端在 5173，`/api` 轉給 8090。第一次先裝依賴：

```bash
corepack enable && pnpm --dir web install
```

```bash
pnpm --dir web run dev
```

## 測試

跑前後端單元測試、後端整合測試、前端 E2E，跟 CI 同一個容器。需要 Docker；模型沒開時，要模型的那幾支會跳過：

```bash
ci/run.sh
```

只有第一次：開啟 pre-push hook，之後每次 push 前先掃祕密、再跑 `ci/run.sh`：

```bash
git config core.hooksPath .githooks
```

每種測試怎麼單獨跑（含 AI eval、壓力測試、雲端冒煙測試）見 [docs/testing.md](docs/testing.md)，CI 檢查什麼見 [ci/README.md](ci/README.md)。
