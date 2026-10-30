# 外部 heartbeat 斷了（`meta-watchdog`）

| | |
|---|---|
| severity | 外部服務寄的信，不經過 Grafana |
| 規則 | `meta-monitoring.yaml` 第 4 條（PR #175，#40）：一條**永遠在響**的 Watchdog，每分鐘打一次 `HEARTBEAT_URL` |
| 怎麼收到 | Healthchecks.io 超過寬限時間（5 分鐘）沒收到 ping，寄信說這個 check 是 **down** |

Watchdog 在 Grafana 上一直是 Firing 是**正常的**，不用處理。這份 runbook 是給「外部服務寄信說 heartbeat 斷了」的時候用。

## 代表什麼

Grafana 已經超過約 6 分鐘沒有送出 heartbeat。可能是：

- Grafana 停了 → **所有告警都不會寄**
- 整台機器斷線或關機
- Grafana 活著但通知送不出去（網路、HEARTBEAT_URL 錯）

## 對使用者的影響

不一定。機器整台斷線的話服務也掛了；只有 Grafana 停的話服務照常、但沒有人在看。

## 怎麼確認

1. **是不是排程關機忘了按 Pause**（雲端平常關機）：AWS Console 看 instance 是不是 stopped。是的話這是預期的 —— 到 Healthchecks.io 按 Pause 就好
2. 服務本身：開網址看能不能用
3. 連上機器（本機就直接）：

   ```bash
   cd ops && docker compose ps grafana
   docker compose logs --since 15m grafana | grep -iE "webhook|heartbeat|level=error" | tail -20
   ```

## 可能的原因（由常見到少見）

### 1. 機器關機了（排程關機、忘了 Pause）

確認是預期的關機 → Healthchecks.io 按 Pause。開機後第一個 heartbeat 會讓 check 自動恢復。

### 2. Grafana 停了

```bash
cd ops && docker compose up -d grafana           # 雲端：oc up -d grafana
```

起來後 Alerting 頁看一下：停掉的這段時間有沒有本該響的告警（現在會補響）。

### 3. 機器活著、Grafana 也活著，但 heartbeat 送不出去

Grafana log 有 `webhook` 送不出去的 error：

- `HEARTBEAT_URL` 沒設或打錯（log 會看到 `.invalid`）→ 修 `deploy/.env`（雲端 `heartbeat_url`、`terraform apply`、重跑 deploy.sh）
- 機器連不到外網 → 其他對外的功能（Bedrock、Google 登入）多半也壞了，看 VPC、security group 的 egress

### 4. 整台機器當掉（雲端）

SSM 連不進去 → AWS Console 看 status check，stop／start。

## 怎麼確認已經恢復

- Healthchecks.io 的 check 回到 **up**（收到新的 ping），會寄一封恢復信
- Grafana 的 Alerting 頁正常，Watchdog 是 Firing

## 什麼時候往上升級

機器開不起來、資料可能有問題 → 照 [備份與還原](backup-restore.md)。

## 事後要不要寫 postmortem

- 原因 1：不必（但忘了 Pause 很多次的話，把 Pause 寫進關機的腳本）
- 原因 2～4：**要**，這段時間完全沒有告警

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-19 | 驗證（#40）：停掉 Grafana，自架的 Healthchecks 寄信 | 見 PR #175 | |
