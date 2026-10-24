# Runbook

告警響了，照著做就能處理，不靠記性（[#41](https://github.com/Anderson-chen/talkcal/issues/41)）。
每一條告警一份，檔名就是告警的 uid；告警信裡的 **runbook_url** 直接連到這裡。告警本身的式子、門檻、為什麼要有，見 [告警總表](../alerts.md)。

| 告警 | severity | Runbook |
|------|----------|---------|
| talkcal app 掛了或抓不到指標 | critical | [talkcal-app-down](talkcal-app-down.md) |
| talkcal 資料庫連不上 | critical | [talkcal-db-down](talkcal-db-down.md) |
| SLO 快燒 | critical | [slo-fast-burn](slo-fast-burn.md) |
| SLO 慢燒 | warning | [slo-slow-burn](slo-slow-burn.md) |
| talkcal 5xx 比例超過 5% | warning | [talkcal-5xx-ratio](talkcal-5xx-ratio.md) |
| talkcal 行程 API p95 延遲超過 1 秒 | warning | [talkcal-api-p95](talkcal-api-p95.md) |
| talkcal AI 助理 p95 延遲超過 30 秒 | warning | [talkcal-assistant-p95](talkcal-assistant-p95.md) |
| talkcal 有請求等不到資料庫連線 | warning | [talkcal-db-pool-timeout](talkcal-db-pool-timeout.md) |
| talkcal 模型熔斷持續打開 | warning | [talkcal-model-circuit-open](talkcal-model-circuit-open.md) |
| talkcal 資料庫備份太久沒成功 | warning | [talkcal-backup-stale](talkcal-backup-stale.md) |
| Alloy 停了，指標不再進來（#40） | critical | [meta-alloy-down](meta-alloy-down.md) |
| Mimir 查不到（#40） | critical | [meta-mimir-down](meta-mimir-down.md) |
| Loki 或 Tempo 抓不到（#40） | warning | [meta-backend-down](meta-backend-down.md) |
| 外部 heartbeat 斷了（#40 的 Watchdog） | — | [meta-watchdog](meta-watchdog.md) |

不是告警觸發、而是要主動做的：[備份與還原](backup-restore.md)。

## 每一份都長一樣

1. **代表什麼**：這條告警成立時，系統哪裡處在什麼狀態
2. **對使用者的影響**：誰、哪個功能、會看到什麼
3. **怎麼確認**：Grafana 哪個面板、哪個查詢、哪條 log —— 先確認是真的，再動手
4. **可能的原因**：由常見到少見，每一種附可以直接複製的指令
5. **怎麼確認已經恢復**
6. **什麼時候往上升級**
7. **事後要不要寫 postmortem**

## 共用：先連上去

### 本機（`deploy/` 那組 compose）

```bash
cd deploy
docker compose ps                      # 每個服務的狀態、healthy 與否
docker compose logs --since 15m app    # 換成 postgres、llm-chat、web 看別的
```

Grafana：<http://localhost:3000>。PromQL 也可以直接打 Mimir：

```bash
curl -s 'http://localhost:9009/prometheus/api/v1/query' --data-urlencode 'query=up'
```

### 雲端（EC2）

在本機（`infra/envs/1-ec2/` 裡）：

```bash
# 連上機器
aws ssm start-session --target "$(terraform output -raw instance_id)" --region ap-northeast-1 --profile DEV
# 另開一個終端機：把機器上的 Grafana 接回本機 http://localhost:3000
$(terraform output -raw grafana_tunnel)
```

連上之後（機器上）：

```bash
cd /opt/talkcal/repo
# 下面的 runbook 寫 `dc` 的地方就是這一串（bedrock；gpu 把最後一個換成 compose.gpu.yaml）
alias dc='sudo docker compose -f deploy/compose.yaml -f infra/envs/1-ec2/compose.ec2.yaml -f infra/envs/1-ec2/compose.bedrock.yaml'
alias oc='sudo docker compose --env-file deploy/.env -f ops/compose.yaml'   # 觀測那組
dc ps
```

本機的 runbook 指令寫 `docker compose`（在 `deploy/` 或 `ops/` 底下跑）；雲端把它換成 `dc`／`oc`。

### 看 log 的基本查詢（Grafana → Explore → Loki）

| 要看什麼 | LogQL |
|---|---|
| app 的錯誤 | `{service="app"} \| json \| log_level="ERROR"` |
| 某一個請求 | `{service="app"} \|= "<traceId>"` |
| 回 5xx 的請求（access log） | `{service="app"} \| json \| http_response_status_code >= 500` |
| 資料庫 | `{service="postgres"}` |

## 升級

只有一個人值班（作者本人），「往上升級」在這裡的意思是**停下來換一種做法**，不是打給別人：

- **critical 處理超過 30 分鐘還沒恢復**：先止血（回到上一版、把助理關掉、維護頁面），不要繼續在線上找原因。
- **要動到資料**（還原、手動改資料庫）：先備份一份（`bash deploy/backup/pg-backup.sh`），照 [備份與還原](backup-restore.md) 做。
- **原因在外部**（AWS、Bedrock、Google 登入）：開 AWS Health Dashboard／Bedrock 的 Service Health 確認，沒辦法修就等，並在畫面上說明（#43 的失敗訊息已經會說）。

## 什麼時候寫 postmortem

寫在 [docs/postmortems/](../postmortems/)，照 [2026-10-12](../postmortems/2026-10-12-deploy-cuts-in-flight-requests.md) 那份的格式（blameless：時間軸、根因、修正的代價、後續行動）。

| 情況 | 要不要寫 |
|------|----------|
| critical 告警、使用者真的受影響 | **要** |
| 同一條告警 30 天內響第二次 | **要**（同一個問題不該發生第二次） |
| 告警響了但其實沒事（誤報） | **要寫一段**：記在這條 runbook 的「誤報紀錄」，改門檻或式子 |
| warning、自己恢復、沒有使用者受影響 | 不必，在 runbook 的「紀錄」補一行日期和原因就好 |
