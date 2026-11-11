# 自動化腳本地圖

每支腳本什麼時候跑、誰叫它。順序照一個改動從 push 到上線、再到出事。
參數和設計理由看腳本開頭的註解。

「just」那欄是 [justfile](../justfile) 裡對應的指令；空白的不是給人直接跑的。`just` 會列出全部，要裝什麼寫在 justfile 開頭；VS Code 裡也能從「Tasks: Run Task」選（[.vscode/tasks.json](../.vscode/tasks.json)）。

```mermaid
flowchart LR
    push["git push"] --> hook["pre-push hook"]
    hook --> gh["GitHub Actions<br/>PR、master、每天"]
    gh -->|"master 綠燈"| watch["本機 watcher<br/>每 5 分鐘"]
    gh -.->|"手動，SSM 連進去"| ec2["EC2 deploy.sh"]
    ec2 --> timer["EC2 systemd<br/>每小時、關機前備份"]
    timer -.->|"出事時，手動"| restore["還原"]
```

## 1. push 之前（本機）

[.githooks/pre-push](../.githooks/pre-push) 在每次 `git push` 前跑。每個 clone 要先開一次：`git config core.hooksPath .githooks`。

| 腳本 | 用途 | 觸發方式 | just |
|---|---|---|---|
| [ci/secrets.sh](../ci/secrets.sh) | gitleaks 掃要推出去的 commit 有沒有 key | pre-push（只掃還沒推的）、CI（整個歷史） | `just secrets` |
| [ci/run.sh](../ci/run.sh) | 在乾淨的容器裡跑 `ci/checks.sh`；改到 prompt、模型相關檔案時加跑 AI eval | pre-push、CI 的 `checks`、手動 | `just ci` |
| [ci/checks.sh](../ci/checks.sh) | CI 的檢查項目都寫在這：ShellCheck、架構圖的錨點、後端測試、前端 lint 和測試、打包、E2E | `ci/run.sh`（在容器裡） | |
| [docs/architecture/lines.mjs](architecture/lines.mjs) | 架構圖的錨點換成行號；`--check` 只檢查每個都對得上 | `ci/checks.sh`、`pages.yml`、手動預覽 | `just arch`、`just arch <資料夾>` |
| [ci/licenses.sh](../ci/licenses.sh) | 前端會打包出去的套件不能是 GPL、AGPL | `ci/checks.sh` | |

## 2. PR、master（GitHub Actions）

| workflow | 什麼時候 | 跑哪些腳本 |
|---|---|---|
| [ci.yml](../.github/workflows/ci.yml) | 每個 PR、push 到 master | `checks`：`ci/run.sh`；`secrets`：`ci/secrets.sh HEAD`；`migrations`：`ci/migrations.sh`；`rules`：`ci/rules.sh`；`sast`：`ci/sast.sh`；`vulns`：`ci/vulns.sh` + `ci/compose-images.sh` |
| ci.yml（排程） | 每天台灣 09:00 | 只跑 `vulns`，抓新公布的 CVE |
| [alerting.yml](../.github/workflows/alerting.yml) | PR 改到告警規則、Mimir、Alloy、`ops/compose.yaml`；或手動 | `ci/alerting.sh` |
| [pr-issue.yml](../.github/workflows/pr-issue.yml) | PR 開啟、編輯、更新 | 沒有腳本：檢查描述有沒有 `Closes #N` |
| [pages.yml](../.github/workflows/pages.yml) | 每次 push 到 master | `docs/architecture/lines.mjs --out`：錨點換成行號，再把互動架構圖發到 GitHub Pages |

| 腳本 | 用途 | just |
|---|---|---|
| [ci/migrations.sh](../ci/migrations.sh) | 舊的 migration 不能改；新的過 squawk，新舊版本同時在線不會壞 | `just migrations` |
| [ci/rules.sh](../ci/rules.sh) | promtool 檢查 Mimir 錄製規則的語法，並用假資料驗算 SLO、burn rate | `just rules` |
| [ci/sast.sh](../ci/sast.sh) → [ci/sast/analyze.sh](../ci/sast/analyze.sh) | CodeQL 掃自己寫的程式（Java、TypeScript、workflow） | |
| [ci/vulns.sh](../ci/vulns.sh) | Trivy 掃映像檔漏洞和 Dockerfile、compose、Terraform 的危險設定 | `just vulns <映像檔>` |
| [ci/compose-images.sh](../ci/compose-images.sh) | 從 compose 列出 EC2 上會跑的第三方映像檔，交給 `vulns.sh` | |
| [ci/alerting.sh](../ci/alerting.sh)（+ [ci/alerting/fake-app.py](../ci/alerting/fake-app.py)） | 告警端到端：真的造出問題，確認信寄得出來，約 15 分鐘 | `just alerting` |
| [ci/format.sh](../ci/format.sh) | 檢查改過的 Java 檔格式。目前跑不起來，見 [#251](https://github.com/Anderson-chen/talkcal/issues/251) | `just format` |

## 3. 部署、發版、退版

本機每 5 分鐘自己去看 master 有沒有新的綠燈版本（為什麼見 [cd/README.md](../cd/README.md)）。EC2 還是手動部署。

| 腳本 | 用途 | 觸發方式 | just |
|---|---|---|---|
| [cd/triggers/install-local.ps1](../cd/triggers/install-local.ps1) | 在 Windows 工作排程登記 watcher，每 5 分鐘一輪 | 手動跑一次 | |
| [cd/triggers/watch.sh](../cd/triggers/watch.sh) | master 有新的綠燈 commit 就 checkout、部署，結果寫回 GitHub Deployments | 工作排程、手動 | `just watch` |
| [cd/apply.sh](../cd/apply.sh) | 把這台機器的服務換成目前 checkout 的版本，等到 healthy | watcher、手動 | `just deploy` |
| [cd/version.sh](../cd/version.sh) | 算出版本號（release tag 或 commit），映像檔的 tag 和 label 都用它 | `apply.sh`、EC2 的 `deploy.sh` | `just version` |
| [cd/release.sh](../cd/release.sh) | 檢查後在本機打 release tag，不會自己推 | 手動 | `just release` |
| [cd/rollback.sh](../cd/rollback.sh) | 退回機器上已有的舊版本並釘住；列出可退的版本；解除釘住 | 手動 | `just versions`、`just rollback`、`just unpin` |

## 4. EC2（AWS 階段一）

細節見 [infra/envs/1-ec2/README.md](../infra/envs/1-ec2/README.md)。

| 腳本 | 用途 | 觸發方式 | just |
|---|---|---|---|
| [power.sh](../infra/envs/1-ec2/power.sh) | 開機、關機；關機前先暫停 Healthchecks，免得被當成掛掉 | 自己的電腦上手動 | `just ec2-start`、`just ec2-stop` |
| [user-data.sh.tftpl](../infra/envs/1-ec2/user-data.sh.tftpl) | 第一次開機：裝 Docker、clone、寫部署設定，再叫 `deploy.sh` | EC2 第一次開機 | |
| [deploy.sh](../infra/envs/1-ec2/deploy.sh) | 拉版本、從 SSM 拉 `.env`、換版前備份、兩組 compose up、裝備份排程 | user-data；之後用 SSM 連進去手動重跑 | |
| [smoke.sh](../infra/envs/1-ec2/smoke.sh) | 部署的冒煙測試：起容器前查 `.env`，起來後查網站、觀測、Bedrock、告警信 | `deploy.sh`（兩次） | |
| [backup-to-s3.sh](../infra/envs/1-ec2/backup-to-s3.sh) | 叫 `pg-backup.sh` 備份，再傳到 S3 | systemd：每小時、關機前；`deploy.sh` 換版前 | |

## 5. 備份、還原

步驟和 RPO／RTO 見 [docs/runbooks/backup-restore.md](runbooks/backup-restore.md)。

| 腳本 | 用途 | 觸發方式 | just |
|---|---|---|---|
| [deploy/backup/pg-backup.sh](../deploy/backup/pg-backup.sh) | pg_dump、驗完整性、清掉過期的舊檔 | 手動（本機）；EC2 上由 `backup-to-s3.sh` 叫 | `just backup` |
| [deploy/backup/pg-restore.sh](../deploy/backup/pg-restore.sh) | 先還原到暫存庫驗過，才換掉線上的資料庫 | 手動 | `just restore-verify`、`just restore` |
| [deploy/backup/pg-diff.sh](../deploy/backup/pg-diff.sh) | 比兩個資料庫多了、少了、改了哪些列（只讀） | 手動 | `just pg-diff` |
| [deploy/backup/drill.sh](../deploy/backup/drill.sh) | 還原演練：獨立的容器上跑備份 → 誤刪 → 還原，量時間 | 手動 | `just drill` |

## 不在這張表上的

- k6 壓測：看 [perf/README.md](../perf/README.md)。
- Gradle 的 task：看根目錄 README 的「快速開始」「測試」。
- `docs/drills/` 底下演練用的一次性腳本。
