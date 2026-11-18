# talkcal

[![CI](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml/badge.svg)](https://github.com/Anderson-chen/talkcal/actions/workflows/ci.yml)

用對話管理行程的行事曆

[Demo](https://talkcal.13-192-239-148.sslip.io/) · [架構圖](docs/architecture.md#總覽)

## 技術

| 類別 | 技術 |
|---|---|
| 後端 | Java 25、Spring Boot 4.1 |
| 模型 | 本機：llama.cpp + Qwen3-8B；雲端：Bedrock 的 Qwen3-32B |
| 資料庫 | PostgreSQL 18 + Flyway |
| 前端 | Vue 3 + TypeScript + Vite |
| 監控 | Grafana Alloy → Mimir、Loki、Tempo → Grafana |
| 壓測 | k6 |
| 部署 | Docker Compose；雲端用 Terraform 開 AWS EC2 |

## 專案結構

| 資料夾 | 說明 |
|---|---|
| [backend](backend/README.md) | 後端程式碼、Gradle、Dockerfile |
| [web](web/README.md) | 前端程式碼、pnpm、Dockerfile、nginx 設定 |
| [deploy](deploy/README.md) | 前後端、模型、資料庫、舊資料歸檔（RustFS）的 Docker Compose 設定 |
| [ops](ops/README.md) | 系統監控：收集並儲存指標、log、trace，提供 dashboard 和異常告警 |
| [perf](perf/README.md) | k6 壓力測試腳本 |
| [ci](ci/README.md) | CI 檢查腳本：測試、格式、授權條款、金鑰和密碼外洩、弱點掃描 |
| [cd](cd/README.md) | CD 腳本：部署、退版、發版、計算版本號 |
| [infra](infra/README.md) | AWS 基礎設施的 Terraform 設定 |
| [docs](docs/README.md) | 跨資料夾的共用文件 |

## 快速開始

| 環境需求 | 條件 |
|---|---|
| JDK | 25 |
| Node.js | 22，「開發」和單獨執行前端測試才需要；「啟動服務」的前端在 Docker 裡 build |
| Bash | Windows 用 Git Bash |
| Docker | Docker Desktop（WSL 2 後端） |
| NVIDIA 顯卡 | 選用，在本機執行模型才需要。RTX 40 系列、驅動 580 以上可以直接用，其他見「本機模型」第 1 步 |
| just | 選用，`just` 會列出部署、備份、CI 檢查這些常用腳本的指令。安裝：`winget install Casey.Just` |

下面的指令都在專案根目錄執行。

### 啟動服務

1. 建 `deploy/.env`，開啟後填入 `DB_PASSWORD`、`ARCHIVE_ACCESS_KEY`、`ARCHIVE_SECRET_KEY`，最後一個至少 8 個字：

   ```bash
   cp deploy/.env.example deploy/.env
   ```

2. 啟動資料庫和歸檔用的 RustFS：

   ```bash
   docker compose -f deploy/compose.yaml up -d postgres archive archive-init
   ```

3. 執行後端測試、build 映像檔，把 Docker 裡的 `app` 和 `web` 容器換成這份程式碼的版本，等到兩個都 healthy。第一次要下載依賴和基礎映像檔，會比較久：

   ```bash
   backend/gradlew -p backend deploy
   ```

4. 開 <http://localhost:18080>，按「還沒有帳號？註冊」建立帳號。Swagger UI 在 <http://localhost:18090/swagger-ui.html>。

   Google、GitHub 登入要另外設定，見 `deploy/.env.example`。

停止全部服務（資料留在 volume，加 `-v` 才會清掉）：

```bash
docker compose -f deploy/compose.yaml down
```

### 開發

開發時後端和前端直接在主機上執行，各開一個終端機。先做完「啟動服務」的第 1、2 步：`bootRun` 從 `deploy/.env` 讀密碼，再連到第 2 步啟動的資料庫和 RustFS。要用對話助理的話，再做「本機模型」。

後端在 8090：

```bash
backend/gradlew -p backend bootRun
```

前端在 <http://localhost:5173>，`/api` 轉給 8090。第一次先裝依賴：

```bash
corepack enable && pnpm --dir web install
```

```bash
pnpm --dir web run dev
```

### 本機模型（選用）

不啟動模型也能用行事曆，只有對話助理不能用。

1. 查顯卡是哪一代（compute capability）和驅動版本：

   ```bash
   nvidia-smi --query-gpu=compute_cap,driver_version --format=csv,noheader
   ```

   印出 `8.9`（RTX 40 系列）和 580 以上的驅動版本就跳過這一步，例如 `8.9, 581.29`。其他情況要改 `deploy/llama-server/Dockerfile`：

   - 第一個數字不是 `8.9`：`CUDA_ARCH` 改成這個數字去掉小數點的值，例如 RTX 30 系列印出 `8.6`，就改成 `86`。
   - 驅動低於 580：照 `CUDA_VERSION` 上面那行註解改。

2. 下載模型（約 5 GB，來源 [Qwen/Qwen3-8B-GGUF](https://huggingface.co/Qwen/Qwen3-8B-GGUF)）。`<模型資料夾>` 換成要放模型檔的絕對路徑，例如 `C:/llm/models`：

   ```bash
   curl -L --create-dirs -o <模型資料夾>/Qwen3-8B-Q4_K_M.gguf https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf
   ```

   再把 `deploy/.env` 的 `MODELS_DIR` 設成同一個路徑；用 `C:/llm/models` 的話不用設，那是預設值。

3. 寫校驗檔，模型複製進容器時會照校驗檔驗內容（已經有這個檔案的話，改成手動加這一行，不然會整份蓋掉）：

   ```bash
   echo "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785  Qwen3-8B-Q4_K_M.gguf" > <模型資料夾>/SHA256SUMS
   ```

4. 啟動模型（第一次要 build 映像檔，約 10 分鐘）：

   ```bash
   docker compose -f deploy/compose.yaml up -d llm-chat
   ```

   `llm-chat` healthy 之後對話助理就能用，app 不用重新啟動。

   `llm-chat` 無法啟動時，確認容器裡偵測得到顯卡，要印出 `GPU 0: NVIDIA ...`：

   ```bash
   docker compose -f deploy/compose.yaml run --rm --no-deps --entrypoint nvidia-smi llm-chat -L
   ```

## 測試

執行前後端單元測試、後端整合測試、前端 E2E，跟 CI 同一個容器。需要 Docker；模型沒開時，要模型的那幾支會跳過：

```bash
ci/run.sh
```

只有第一次：開啟 pre-push hook，之後每次 push 前先掃描金鑰和密碼、再執行 `ci/run.sh`：

```bash
git config core.hooksPath .githooks
```

每種測試怎麼單獨執行（含 AI eval、壓力測試、雲端部署後的檢查）見 [docs/testing.md](docs/testing.md)，CI 檢查什麼見 [ci/README.md](ci/README.md)。
