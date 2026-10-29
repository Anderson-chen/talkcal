# Runbook 演練紀錄（2026-10-20，#41）

造出故障，讓告警真的寄信，再**只照 runbook** 一步一步處理到恢復。時間都是 UTC（台灣時間 +8，也就是 2026-10-20 04:05～04:40）。

## 環境

沒碰共用的 `talkcal-*` 容器，另外起一組 `a39-*`：同一份 `ops/compose.yaml`（這個分支的版本，規則都帶 `runbook_url`），加一份 override 只改容器名、埠、網路。

| 角色 | 用什麼 |
|------|--------|
| app | **真的 app**（`talkcal43/app:local`，#43 的分支建的映像檔，含 #44 的熔斷器）、真的 PostgreSQL 18.6、postgres_exporter v0.20.1 |
| 模型 | 假的 OpenAI 相容服務（固定回一句話）。演練只需要「模型會回、會掛」 |
| 流量 | 一個腳本：登入一次，每秒列一次月曆，每 6 秒跟助理說一句（並行，卡住的不擋後面的） |
| 信箱 | Mailpit，收件人 `oncall@example.test`，沒寄到任何真的信箱 |

**跟正式環境不同的地方**：測試環境的 app 不屬於 `talkcal-deploy` 這個 compose 專案，Alloy 照設定不收它的 log，所以 runbook 裡的 Loki 查詢改用 `docker logs` 看同一份內容（內容一樣是 ECS JSON）。Runbook 寫的 `docker compose …` 在這裡是同一組參數加上 `-p a39-obs`。

## 反例：正常狀態下，確認步驟不會誤判成出事（20:04:58）

照兩份 runbook 的「怎麼確認」跑一遍，結果都指向「沒事」：

| 步驟 | 結果 |
|------|------|
| `pg_up{job="postgres"}`、`up{job="postgres"}` | 1、1 |
| 5xx 比例 | 沒有序列（沒有 5xx） |
| `/actuator/health` | `{"status":"UP"}` 200 |
| 熔斷狀態 | `closed` = 1 |
| 熔斷失敗率 | **-1** → 看到這個值會以為有問題，其實是「不到 5 次、還不夠判斷」。**已補進 runbook** |
| 被擋下的請求 | 0 |

同一時間告警規則全部 Normal。

## 演練 1：資料庫連不上（[runbook](../runbooks/talkcal-db-down.md)）

| 時間 | 事件 |
|------|------|
| 20:05:10 | **故障**：`docker stop postgres` |
| 20:05:10～ | 使用者：列月曆每個都等滿 30 秒才拿到 502（連線池逾時） |
| 20:06:35 | 收到 `[FIRING] 有請求等不到資料庫連線`（warning）—— 比資料庫那封還早 |
| 20:07:32 | 收到 **`[FIRING] talkcal 資料庫連不上`**（critical），信裡有 **View runbook** 按鈕 |
| 20:09:16 | 收到 `[FIRING] SLO 快燒`（`calendar-availability`） |
| 20:12:05 | 開始照 runbook（這段延遲是演練操作的空檔，不是 runbook 的問題） |
| 20:12:05 | 確認 1：`pg_up` = 0、`up` = 1 → exporter 活著、是**資料庫**連不上 |
| 20:12:05 | 確認 2：health `DOWN` 503；確認 3：postgres `Exited (0)` |
| 20:12:05 | 原因 1：postgres log 是正常的 `shutting down`（不是 OOM、不是磁碟滿）→ 被停掉的 |
| 20:12:42 | 處理：`docker compose up -d --wait postgres` |
| 20:12:44 | postgres healthy；**app 沒重啟**，HikariCP 自己重連，health `UP`、列月曆 200 |
| 20:17:32 | 收到 `[RESOLVED] talkcal 資料庫連不上` |

- 故障到收到 critical 信：**2 分 22 秒**（規則持續 1 分鐘＋評估間隔＋寄信湊批 30 秒）
- 照 runbook 從確認到恢復：**39 秒**
- 影響：12 個列月曆的請求失敗（每個等 30 秒）

![資料庫連不上的告警信，下面有 View runbook](2026-10-20-drill-db-down-email-runbook.jpg)

**這次學到、已改進 runbook 的**：同一件事會連帶收到好幾封（等不到連線、SLO 快燒、SLO 慢燒），值班的人要知道「都是同一個原因」，不用分頭查 —— 補在 runbook 的「對使用者的影響」。

## 演練 2：模型熔斷持續打開（[runbook](../runbooks/talkcal-model-circuit-open.md)）

| 時間 | 事件 |
|------|------|
| 20:20:58 | **故障**：`docker stop llm-chat` |
| 20:22:05 | 流量腳本的助理請求改成並行後重開（第一版是單執行緒，一個請求卡住就整個停，湊不到失敗次數） |
| 20:22:28 | app log：`State transition from CLOSED to OPEN` |
| 20:22:30～ | 使用者：助理多半幾毫秒就拿到 503 `MODEL_UNAVAILABLE`；每 30 秒半開試 2 個，試的那個拿到 502 |
| 20:28:37 | 收到 **`[FIRING] talkcal 模型熔斷持續打開`**，summary「擋下 34 個 AI 助理請求」，有 View runbook |
| 20:32:44 | 開始照 runbook |
| 20:32:44 | 確認 2：state `open`、失敗率 100%、慢呼叫率 0% → 是**連不上／回錯誤**，不是卡住 |
| 20:32:44 | 確認 3：log 一直在 OPEN ↔ HALF_OPEN 間跳；ERROR 的 message 只有「Request failed」，**原因在 stack trace 的 `Caused by: java.net.UnknownHostException: llm-chat`** → 原因 1（容器不在） |
| 20:33:42 | 處理：`docker compose up -d llm-chat` |
| 20:34:01 | log：`State transition from HALF_OPEN to CLOSED`；助理回 200 |
| 20:38:37 | 收到 `[RESOLVED] talkcal 模型熔斷持續打開` |

- 熔斷打開到收到信：**6 分 9 秒**（「5 分鐘沒關回來」＋評估＋湊批）
- 照 runbook 從確認到恢復：**1 分 17 秒**
- 影響（20:20:58～20:34:01）：助理 40 個 502、77 個 503；**列月曆 712 個全部 200** —— 行程功能照常

![模型熔斷的告警信](2026-10-20-drill-model-circuit-email-runbook.jpg)

**這次學到、已改進 runbook 的**：

1. ERROR log 的 message 看不出原因，要看 stack trace 最後的 `Caused by` —— 補了一張「`Caused by` → 哪個原因」的對照表
2. 熔斷失敗率 -1 的意思（上面的反例）

## 附帶發現

- **Watchdog 寄了一封 email**（20:05:15）：#175 的設計是 Watchdog 只走 `notification_settings` 送 heartbeat。之後每 30 秒都正常送 heartbeat（測試環境沒設網址，Grafana log 是送不出去的錯誤），沒有再寄 email；只有 Grafana 換上新設定、第一次評估那一次。看起來是規則剛建立時繞到了預設 policy。影響很小（換設定時多一封信），記給 #175。
- 資料庫停掉時，「有請求等不到資料庫連線」比「資料庫連不上」**早**一分鐘到：有流量時逾時馬上就發生，資料庫那條要撐滿 1 分鐘。兩條都寄是對的（一條說使用者受影響、一條說原因）。
