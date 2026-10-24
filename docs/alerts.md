# 告警總表

出事時有人被叫起來，不是等使用者來說（[#39](https://github.com/Anderson-chen/talkcal/issues/39)）。
這份表列出**所有**告警：在哪個檔案、怎麼算、多久才叫人、叫誰，以及為什麼要有它。新增或改門檻時一起改這裡。

規則都用 provisioning 管（`ops/grafana/provisioning/alerting/`），Grafana 一啟動就建好、UI 上唯讀。
每條門檻的完整理由寫在規則檔的註解，這裡只寫一句話。
**告警響了怎麼處理**：每一條都有一份 runbook，檔名就是 uid（[docs/runbooks/](runbooks/README.md)），告警信裡的 **View runbook** 直接連過去。

## 怎麼分工

```
                使用者已經受影響？
                ┌───────────────┴───────────────┐
               是（症狀）                        還沒，或量不到（原因）
   SLO burn rate、5xx 比例、p95、app 掛了     資料庫、連線池、模型熔斷、備份
   → 真正叫醒人的是這些                        → 只在「低流量時症狀看不到」或「一眼指出處理方向」時才加
                └───────────────┬───────────────┘
                     監控本身（Alloy、Mimir、Loki、Tempo、Grafana）
                     → 上面全部都靠它，它停了要另外發現（#40）
```

Google SRE 的原則是**對症狀告警、不對原因告警**：原因有一百種，症狀只有「使用者用不了、變慢」幾種。
原因類的告警（`respond.yaml`）只在兩種情況加：

1. **低流量時症狀看不到**：半夜沒人用，資料庫掛了也不會有 5xx、SLO 也不會燒，第一個早上打開的使用者才發現。
2. **原因一眼就指出處理方向**：SLO 只說「助理可用性在燒」，熔斷告警直接說「是模型」，不用從 5xx 一路往下追。

## 通知

| severity | 意思 | 現在送去哪 |
|----------|------|-----------|
| `critical` | 現在就要處理（Workbook 說的 page） | email（`policies.yaml`） |
| `warning` | 上班時間處理（Workbook 說的 ticket） | email（`policies.yaml`） |
| （無） | Watchdog，不是事件 | heartbeat → 外部服務（#40） |

**兩種 severity 現在都寄同一個信箱**：只有一個人、一個管道，分流沒有意義。
規則都已經帶好 `severity`，之後要讓 `critical` 打電話（PagerDuty、Grafana OnCall）時，只要在 `policies.yaml` 加一條比對 `severity=critical` 的子 policy，規則不必動。
寄信節奏：同一條規則的告警合成一封、第一次等 30 秒湊批、新告警加入最快 5 分鐘再寄、沒修好每 4 小時提醒（`policies.yaml`）；恢復時也寄一封。

## 總表

「沒資料時」是查詢查不到任何序列時當成什麼：「出事」= `noDataState: Alerting`，「正常」= `OK`。
**通知誰**：除了 Watchdog（送外部 heartbeat），每一條都走 `policies.yaml` 寄到 `ALERT_EMAIL_TO`，所以表裡不另列。

| 告警（uid） | 檔案（PR） | 式子（摘要） | 門檻 | 持續 | 沒資料時 | severity | 為什麼要有 |
|---|---|---|---|---|---|---|---|
| `talkcal-app-down` app 掛了或抓不到指標 | `rules.yaml`（#152） | `max(up{job="talkcal-app"})` | < 1 | 1m | **出事** | critical | 最根本的「活著嗎」。app 掛掉時 SLO 是「沒資料」不是「壞」，只有這條看得到 |
| `talkcal-5xx-ratio` 5xx 比例 | `rules.yaml`（#152） | 5xx ÷ 全部（5 分鐘至少 20 個請求） | > 5% | 5m | 正常 | warning | SLO 之前的暫時版，照實測留餘裕；SLO 上線後跟快燒重疊，保留作為「不分 API」的總覽 |
| `talkcal-api-p95` 行程 API p95 | `rules.yaml`（#152） | `histogram_quantile(0.95, …)`，不含助理 | > 1s | 10m | 正常 | warning | 同上；實測 p95 約 0.18 秒，1 秒是使用者明顯覺得卡的線 |
| `talkcal-assistant-p95` AI 助理 p95 | `rules.yaml`（#152） | 同上，只看 `/api/calendar/assistant` | > 30s | 10m | 正常 | warning | 同上；實測約 3 秒，30 秒時使用者多半已經放棄 |
| `slo-fast-burn` SLO 快燒 | `slo-burn-rate.yaml`（#154） | `slo:burn_rate:1h`＋`5m` > 13.44，或 `6h`＋`30m` > 5.6 | > 0（有任一 SLO 成立） | 0s | 正常 | critical | 照這速度 2～5 天燒光 28 天的 budget。症狀告警的主角，四個 SLO 各一個實例 |
| `slo-slow-burn` SLO 慢燒 | `slo-burn-rate.yaml`（#154） | `1d`＋`2h` > 2.8，或 `3d`＋`6h` > 0.933 | > 0 | 0s | 正常 | warning | 不急但這個月會不達標，開 issue 上班處理 |
| `talkcal-db-down` 資料庫連不上 | `respond.yaml`（#39） | `min(pg_up{job="postgres"})`，exporter 抓不到也算 0 | < 1 | 1m | 正常 | critical | 低流量時沒有 5xx、SLO 不燒；exporter 每 15 秒自己連一次，不靠使用者 |
| `talkcal-db-pool-timeout` 等不到資料庫連線 | `respond.yaml`（#39） | `sum(increase(hikaricp_connections_timeout_total[5m]))` | > 0.5 | 0s | 正常 | warning | 每一次都是使用者等滿 30 秒拿到 502，正常時是 0；方向明確：資料庫那頭在等鎖或變慢，加大池子沒用（#32） |
| `talkcal-model-circuit-open` 模型熔斷持續打開 | `respond.yaml`（#39） | 近 5 分鐘擋下的請求數（`not_permitted_calls`），而且這 5 分鐘一次都沒關回來（`state="closed"` 的最大值是 0） | > 0.5 | 0s（5 分鐘沒關回來本身就是等待） | 正常 | warning | SLO 會響但不說是模型；熔斷直接指向模型。行程照常，所以是 warning，要不要半夜起來交給 SLO 快燒 |
| `talkcal-backup-stale` 備份太久沒成功 | `respond.yaml`（#39） | `time() − 最後成功備份`，而且開機也超過 2h15m | > 2h15m | 0s | 正常 | warning | 備份失敗只寫在 journalctl，沒人看；等要還原那天才發現最後一份是兩週前的就晚了 |
| `meta-alloy-down` Alloy 停了 | `meta-monitoring.yaml`（#175） | `time() − max(timestamp(up{job="alloy"}))` | > 60s | 2m | **出事** | critical | 三種訊號都經過 Alloy，它停了 dashboard 一片空白，看起來像「沒事」 |
| `meta-mimir-down` Mimir 查不到 | `meta-monitoring.yaml`（#175） | `vector(1)`，查詢失敗就算出事 | 查詢失敗 | 2m | **出事** | critical | 所有指標類告警都靠 Mimir，它查不到時上面每一條都不會響 |
| `meta-backend-down` Loki 或 Tempo 抓不到 | `meta-monitoring.yaml`（#175） | `min by (job) (up{job=~"loki\|tempo"})` | < 1 | 2m | 正常 | warning | 少一種訊號，告警本身還在，不必半夜叫人 |
| `meta-watchdog` Watchdog | `meta-monitoring.yaml`（#175） | `vector(1)`，永遠在響 | — | 0s | **出事** | （無） | dead man's switch：Grafana 或整台機器停了，heartbeat 斷掉，由外部服務寄信 |

> #40（監控本身掛掉）的四條在 PR #175（分支 `claude/40-meta-monitoring-4a7832`），還沒合併；合併時以它的檔案為準。

## 新加的四條怎麼接的（#39）

| 告警 | 指標從哪來 | 依賴 |
|------|-----------|------|
| 資料庫連不上 | postgres_exporter 的 `pg_up`、Alloy 的 `up{job="postgres"}` | #146（#32，接 exporter）；沒合併前查詢是空的 → 正常，不會誤響 |
| 等不到資料庫連線 | app 的 HikariCP（Micrometer 自動產生） | 無，master 上就有 |
| 模型熔斷持續打開 | app 的 Resilience4j（`resilience4j-micrometer`） | #161（#44，熔斷器）；沒合併前查詢是空的 → 正常 |
| 備份太久沒成功 | `backup-to-s3.sh` 成功時寫 `/opt/talkcal/metrics/backup.prom`，Alloy 的 textfile collector 讀；開機時間由同一個 exporter 的 stat collector 給 | #159（#45，備份）。只有雲端有；本機手動備份不寫，查詢是空的 → 正常 |

**備份為什麼用 textfile**：備份是跑完就結束的批次工作，沒有 `/metrics` 可以抓。Prometheus 對這種工作的建議是
node_exporter 的 textfile collector（工作結束時寫一個 `.prom` 檔）；另一個選項 Pushgateway 要多養一個服務，
而且一樣只保留最後一次的值，沒有比較好。Alloy 內建同一個 collector（`prometheus.exporter.unix`），不必多一個容器。

**為什麼跟開機時間比**：機器平常關機。開機時最後一次備份可能是一週前關機前那份，那不是失敗；
要盯的是「開機之後的整點備份有沒有成功」。所以「距離上次成功」和「距離開機」都超過門檻才算。

**為什麼熔斷不直接看 `state="open"`**：熔斷打開 30 秒後，要等下一個請求來才會轉成半開（沒開自動轉換）。
半夜一次抖動之後沒人用的話，狀態會一直停在 open，模型早就好了告警卻一直響 —— 第一版就是這樣，反例實測抓到的。
所以改成「真的有人被擋下」而且「5 分鐘都沒關回來」。

## 考慮過、沒加的

| 情況 | 為什麼沒加 |
|------|-----------|
| 模型伺服器（llama-server）抓不到 | 只有本機有（雲端是 Bedrock）。有人在用時熔斷會打開、SLO 會燒；沒人用時模型掛了也沒有人受影響 |
| JVM heap、GC 時間 | 原因類、而且不指出處理方向；真的撐不住會變成 p95 變慢或 OOM 重啟（app 掛了） |
| 連線池 pending 偏高（還沒逾時） | #32 實測鎖表 20 秒時 pending 衝到 40、但全部成功，使用者只是等了一下；逾時才是「有人失敗」 |
| **主機磁碟快滿** | **應該要有**：磁碟滿了 PostgreSQL 寫不進去、Mimir 停止收資料。但 Alloy 在容器裡量到的檔案系統不一定是主機的，要在主機上另外裝 node_exporter（雲端 user_data），範圍比這次大，另開 issue |
| Bedrock 被限流 | 會變成模型呼叫失敗或變慢，由熔斷、SLO 涵蓋 |

## 怎麼驗證的

每一條新告警都造出該響的情況，也證明正常時不響。驗證時間軸與截圖在 PR 描述（#39）。
