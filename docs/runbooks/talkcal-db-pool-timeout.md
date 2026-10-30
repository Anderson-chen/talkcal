# talkcal 有請求等不到資料庫連線（`talkcal-db-pool-timeout`）

| | |
|---|---|
| severity | warning —— 上班時間處理；同時 SLO 快燒或 5xx 也響的話，照那條的 critical 處理 |
| 規則 | [`respond.yaml`](../../ops/grafana/provisioning/alerting/respond.yaml) 第 2 條，總表見 [alerts.md](../alerts.md) |
| 條件 | 近 5 分鐘 `hikaricp_connections_timeout_total` 增加了（> 0.5），一成立就寄 |

## 代表什麼

app 的連線池（HikariCP，預設 10 條）全部被借走，新的請求排隊等了 `connectionTimeout`（30 秒）還是借不到，放棄。
正常情況這個數字一直是 0（#32 實測：只壓讀取時連線平均只借 3.6ms，池子遠遠用不滿）。

連線池滿 **幾乎都是資料庫那頭變慢**：每條連線借出去後還不回來（在等鎖、查詢變慢），不是池子太小。
#32 的結論：「app 的 pending 高、資料庫 active = 池子大小」→ 問題在資料庫，加大池子只會讓更多連線一起卡住。

## 對使用者的影響

每一次逾時都是**一個使用者等了整整 30 秒，最後拿到錯誤**（502）。其他還在排隊的人也都在等。
同時期所有要用資料庫的 API 都會變慢：行程、登入、AI 助理。

## 怎麼確認

1. **talkcal-app dashboard 的「資料庫連線池」那一排**（#32）：
   - active 貼著 max（紅虛線）、pending 衝高 → 池子被占滿
   - acquire（借連線花多久）衝到秒級
2. 同一排的 **PostgreSQL 連線狀態**（`pg_stat_activity`）：
   - `active` 等於池子大小、而且一直沒降 → 連線在資料庫裡卡住
   - `idle in transaction` 很多 → app 開了交易沒結束
3. 查詢（Explore → Mimir）：

   ```promql
   hikaricp_connections_active{job="talkcal-app"}
   hikaricp_connections_pending{job="talkcal-app"}
   sum(increase(hikaricp_connections_timeout_total{job="talkcal-app"}[5m]))
   ```

4. 資料庫裡現在誰在等誰（直接問 postgres）：

   ```bash
   docker compose exec postgres psql -U talkcal -d talkcal -c "
     SELECT pid, state, wait_event_type, wait_event, now() - query_start AS running, left(query, 80) AS query
     FROM pg_stat_activity WHERE datname = 'talkcal' AND state <> 'idle' ORDER BY running DESC;"
   ```

## 可能的原因（由常見到少見）

### 1. 有東西鎖住表（migration、手動 SQL、忘了 commit 的交易）

上面的查詢看到很多 `wait_event_type = Lock`，其中一個 `running` 特別久的是罪魁禍首。找出擋路的那一個：

```bash
docker compose exec postgres psql -U talkcal -d talkcal -c "
  SELECT pid, pg_blocking_pids(pid) AS blocked_by, state, left(query, 80) AS query
  FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0;"
```

`blocked_by` 裡的 pid 就是擋路的。確認它不是正在跑、跑完就好的 migration 之後，結束它：

```bash
docker compose exec postgres psql -U talkcal -d talkcal -c "SELECT pg_terminate_backend(<pid>);"
```

結束一個正在寫的交易，它做到一半的變更會整個撤回，不會留下半套資料。如果它是 migration，先看 app 的 log 確認是哪一版的 Flyway 在跑，不要在 migration 中途砍。

### 2. 某個查詢突然變慢（資料變多、少了索引）

`pg_stat_activity` 裡同一種查詢很多條都在 `active`、沒有在等鎖、只是跑很久。
用 trace 找出是哪個 API：Tempo 搜慢的請求 → 看 SQL 那段 span 的耗時。然後看執行計畫：

```bash
docker compose exec postgres psql -U talkcal -d talkcal -c "EXPLAIN ANALYZE <那條查詢>;"
```

看到 `Seq Scan` 在大表上 → 要加索引（寫成 Flyway migration，走 PR，不要在線上直接 `CREATE INDEX`）。

### 3. 流量突然變大

talkcal-app dashboard 的請求數明顯比平常高，每個請求都正常、只是太多。
目前單機、池子 10 條：#32 量到讀取 1,500 req/s 時是 CPU 先滿，池子還沒滿。真的是流量的話，先看是不是被同一個 IP 打（nginx 的限流應該已經擋了一部分，log 裡會有 429）。

### 4. 資料庫本身很慢（CPU、磁碟 I/O 滿）

```bash
docker stats --no-stream talkcal-postgres
```

CPU 一直在 100%，或雲端 EBS 的 burst credit 用完（CloudWatch 的 `BurstBalance`）。先看是不是原因 2 的慢查詢造成的。

## 怎麼確認已經恢復

- 連線池那一排：active 降回個位數、pending 回到 0
- `sum(increase(hikaricp_connections_timeout_total{job="talkcal-app"}[5m]))` 回到 0，**5 分鐘後**告警自己解除、收到 `[RESOLVED]`
  （5 分鐘的視窗要等最後一次逾時滑出去才會歸零，這段時間告警還掛著是正常的）

## 什麼時候往上升級

- 同時 SLO 快燒（`calendar-availability` 或 `calendar-latency`）→ 照 critical 處理，先止血
- 要結束的連線是 migration、或不確定它在做什麼 → 不要砍，先停下來看 app log 和那次部署的內容
- 加索引、改查詢都要寫程式、走 PR，不在線上改

## 事後要不要寫 postmortem

- 原因 1（有人或 migration 鎖表）：**要**，重點放在「怎麼讓它不再鎖住線上表」（例如 migration 改成不鎖表的寫法，#133）
- 原因 2（慢查詢）：修好那個 PR 的描述裡寫清楚就好
- 只有一兩次逾時、自己恢復：不必，在下面紀錄一行

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-20 | 演練「資料庫連不上」時連帶響（資料庫停了，每個請求等滿 30 秒） | 跟著資料庫恢復 | [演練紀錄](../drills/2026-10-20-runbook-drills.md)；這種情況照 [talkcal-db-down](talkcal-db-down.md) 處理 |
