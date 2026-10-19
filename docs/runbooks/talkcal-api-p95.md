# talkcal 行程 API p95 延遲超過 1 秒（`talkcal-api-p95`）

| | |
|---|---|
| severity | warning —— 上班時間處理 |
| 規則 | [`rules.yaml`](../../ops/grafana/provisioning/alerting/rules.yaml) 第 3 條，總表見 [alerts.md](../alerts.md) |
| 條件 | 行程 API（不含 `/api/calendar/assistant`）近 5 分鐘的 p95 > 1 秒，持續 10 分鐘 |

## 代表什麼

行程的增刪改查，最慢的那 5% 要超過 1 秒，而且持續了 10 分鐘。平常 p95 約 0.18 秒（5 倍流量時 p99 也才 0.15 秒），
慢到 1 秒代表**後面有東西在拖**，幾乎都是資料庫。

## 對使用者的影響

點了行程要等一秒以上才出來，覺得卡。還沒有失敗（失敗的話會是 5xx 那條）。
同時 SLO 的 `calendar-latency`（300ms 內 99%）會在燒。

## 怎麼確認

1. **哪一支慢**：

   ```promql
   histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{job="talkcal-app", uri!="/api/calendar/assistant"}[5m])))
   ```

2. **慢在哪一段**：talkcal-app dashboard 的 p95 圖上點小菱形（exemplar）→ 開那次請求的 trace，看 SQL 的 span 佔多久
3. **是不是在等連線**：連線池那一排的 pending、acquire；有 pending 的話照 [talkcal-db-pool-timeout](talkcal-db-pool-timeout.md) 的「怎麼確認」找鎖
4. **JVM**：talkcal-app dashboard 的 GC 暫停、CPU —— GC 一直在跑代表記憶體快不夠

## 可能的原因（由常見到少見）

### 1. 資料庫在等鎖或查詢變慢

trace 裡 SQL span 佔了大部分時間。照 [talkcal-db-pool-timeout](talkcal-db-pool-timeout.md) 的原因 1、2 找出是哪條查詢、誰在擋。

### 2. 流量變大、CPU 滿了

請求數比平常高很多，`docker stats` 看 app 的 CPU 一直很高。#32 實測讀取在 1,500 req/s 左右 CPU 先滿。
先看是不是異常流量（同一個 IP、爬蟲）—— nginx 的限流會回 429，`{service="web"} |= " 429 "` 看得到。

### 3. JVM 記憶體不夠，一直在 GC

GC 暫停時間很高、heap 貼著上限。重啟 app 可以暫時解（`docker compose restart app`，會中斷進行中的請求幾秒），根本原因要寫 issue 查。

### 4. 機器本身變慢（雲端）

EBS burst credit 用完、CPU credit 用完（t 系列、flex 機型）。CloudWatch 看 `CPUCreditBalance`、`BurstBalance`。

## 怎麼確認已經恢復

- 步驟 1 的 p95 回到 0.2 秒左右；告警要 p95 低於 1 秒的那次評估才解除，收到 `[RESOLVED]`
- SLO 儀表板的 `calendar-latency` burn rate 降下來

## 什麼時候往上升級

- 同時 SLO 快燒（`calendar-latency` 或 `calendar-availability`）→ 照 critical 處理
- 要加索引、改查詢 → 寫 migration、走 PR，不在線上直接改

## 事後要不要寫 postmortem

- 找到明確原因而且改了東西：寫在修正的 PR 裡就好
- 30 天內第二次：**要**

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-17 | 驗證（#152）：假 app 延遲 2 秒 | 恢復後約 9 分鐘解除 | 見 PR #152 |
