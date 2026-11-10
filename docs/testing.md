# 測試

| 測試 | 測什麼 | 要先準備 | 誰會跑 |
|---|---|---|---|
| [前端單元測試](#前端) | Vitest：從元件抽出來的純函式，例如月曆格子的日期、AI 助理失敗的分類 | 不用 | CI、pre-push |
| [前端 E2E](#前端) | Playwright：真的 Chromium 開整頁，桌面、手機兩種版面；API 攔下來回假資料，不需要後端 | 第一次要裝 Chromium | CI、pre-push |
| [後端單元測試](#後端) | domain 規則和 use case、controller（MockMvc）、ArchUnit 的分層規則 | 不用 | CI、pre-push |
| [後端整合測試](#後端) | 用 Testcontainers 開真的 PostgreSQL 和 S3，再接真的模型：資料庫和 S3 的 adapter、整個 app 接起來打 HTTP、跟模型對話、prompt injection | Docker；模型沒開的那幾支會跳過 | CI、pre-push；GitHub 上沒有 GPU，模型那幾支只有本機會跑 |
| [AI eval](#後端) | 一組固定題目跑真模型、計分，跟基準線比 | 模型 | pre-push，只在改到 prompt 或模型相關的檔案時；也可以手動跑 |
| [壓力測試](#壓力測試) | k6 從 HTTP 壓 AI 助理和行程 API：併發上限、尖峰、邊壓邊部署、模型掛掉、連線池 | PostgreSQL、模型、app 都起來，裝好 k6 | 手動 |
| [雲端冒煙測試](#雲端) | 部署到 EC2 之後，從 https 打網站和 `/api`，再查 Grafana 的資料源、Alloy 抓不抓得到 app、Bedrock 問不問得到、告警信寄不寄得出去 | EC2 開機 | 每次在 EC2 上部署（`deploy.sh`） |

表上「CI、pre-push」那幾種用 `ci/run.sh` 一次跑完，見根目錄 [README](../README.md#測試)；覆蓋率怎麼看見 [test-coverage.md](test-coverage.md)。

## 前端

在 repo 根目錄跑，要先裝好依賴（`corepack enable`、`pnpm --dir web install`）：

```bash
pnpm --dir web test                               # 單元測試（Vitest），不到 1 秒
pnpm --dir web exec playwright install chromium   # E2E 要的 Chromium，只要裝一次
pnpm --dir web run e2e                            # E2E（Playwright）
pnpm --dir web run e2e:ui                         # 同上，開視窗一步一步看每個動作前後的畫面
```

寫測試的約定、為什麼要用 `pnpm` 不用 `npx`，見 [web/README.md](../web/README.md#測試)。

## 後端

在 `backend/` 裡跑：

```bash
./gradlew test               # 單元測試和架構規則，不用起任何服務
./gradlew integrationTest    # 整合測試：要開 Docker；模型（快速開始的 llm-chat）沒開的那幾支會跳過
./gradlew eval               # AI eval：要開模型，報告在 build/eval/report.md
./gradlew test integrationTest jacocoTestReport   # 再加上覆蓋率報告，見 docs/test-coverage.md
```

AI eval 沒設定時評本機的 Qwen3-8B；要評雲端的模型，設 `SPRING_AI_OPENAI_CHAT_BASEURL`、`SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`、`SPRING_AI_OPENAI_APIKEY` 再跑。

## 壓力測試

在 repo 根目錄跑。壓的是真的那條路，沒有 mock，所以 PostgreSQL、模型、app 都要先起來：

```bash
k6 run perf/smoke.js   # 先確認打得到 AI 助理、契約對；預設打 bootRun 的 app（8090）
k6 run perf/knee.js    # 同時數一階一階往上加，找臨界值；預設打 docker 那台 app（18090）
```

k6 的裝法、其他腳本（尖峰、邊壓邊部署、模型掛掉演練、SLO 基準、連線池、token 成本）各量什麼、怎麼把指標疊進 Grafana，見 [perf/README.md](../perf/README.md)。

## 雲端

雲端（EC2 + Bedrock）上跑的是部署的冒煙測試 [smoke.sh](../infra/envs/1-ec2/smoke.sh)。`deploy.sh` 會呼叫它兩次：起容器之前查 `.env`，全部起來之後查網站、觀測、Bedrock、告警寄信；任一項沒過，部署就失敗並印出是哪一項。

機器平常關機，先 `just ec2-start` 開機。不部署、只重跑部署後那一輪，連進機器（`aws ssm start-session`）之後：

```bash
cd /opt/talkcal/repo && sudo bash infra/envs/1-ec2/smoke.sh services deploy/.env
```

每一項查什麼、為什麼 CI 測不到，寫在 smoke.sh 開頭；部署和連線的步驟見 [infra/envs/1-ec2/README.md](../infra/envs/1-ec2/README.md#日常操作)。
