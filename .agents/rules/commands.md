# 指令規則

都在 repo 根目錄下打。表裡沒有的腳本，先讀腳本開頭的說明再跑；每種測試要先準備什麼見 [docs/testing.md](../../docs/testing.md)。

## 要開 PR 時，照改到的地方驗證

commit 前不用跑，等要開 PR 時一起跑。這些可以直接跑，不用先問：

| 改到 | 跑 |
|---|---|
| 後端 Java | `backend/gradlew -p backend test`：單元測試、ArchUnit 分層規則，編譯時 Error Prone 也會跑 |
| 後端的 package、import | `backend/gradlew -p backend test --tests '*ArchitectureTest'`：只看依賴方向，最快 |
| 後端的資料庫、S3 adapter、整個 app 的組裝 | `backend/gradlew -p backend integrationTest`（要開 Docker） |
| DB migration | `ci/migrations.sh` |
| prompt、模型相關的程式 | `backend/gradlew -p backend eval`（要開模型） |
| 前端 `web/` | `pnpm --dir web run lint`、`pnpm --dir web test`、`pnpm --dir web run build`：`build` 會跑 `vue-tsc`，型別錯誤只有它抓得到 |
| 前端畫面 | `pnpm --dir web run e2e` |
| `infra/`、`deploy/`、`ops/`、`web/nginx.conf`、`docs/architecture/` | `node docs/architecture/lines.mjs --check` |
| `ops/mimir/rules/` | `ci/rules.sh` |
| 跨好幾塊、準備 push | `ci/run.sh`（要開 Docker）：跟 CI 同一套檢查 |
| 演練還原資料庫，不碰線上資料庫 | `just restore-verify` |

前端指令要先裝好依賴：`corepack enable`、`pnpm --dir web install`。前端用 `pnpm`，不用 `npx`，理由見 [web/README.md](../../web/README.md#測試)。

## 先問使用者才能跑

這些會改到正在跑的服務、資料、雲端費用，或要跑很久。指令照 [justfile](../../justfile)：

| 做什麼 | 指令 |
|---|---|
| 部署、發版、退版 | `just deploy`、`just release`、`just rollback`、`just unpin`、`just watch` |
| 還原線上資料庫 | `just restore` |
| EC2 開關機 | `just ec2-start`、`just ec2-stop` |
| 壓力測試 | `just perf <腳本>`、`k6 run perf/<腳本>` |
| 告警端到端測試（約 15 分鐘） | `just alerting` |
| 推上 GitHub | `git push` |
