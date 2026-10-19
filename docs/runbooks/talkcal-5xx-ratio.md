# talkcal 5xx 比例超過 5%（`talkcal-5xx-ratio`）

| | |
|---|---|
| severity | warning —— 但同時有 SLO 快燒時照 critical 處理 |
| 規則 | [`rules.yaml`](../../ops/grafana/provisioning/alerting/rules.yaml) 第 2 條，總表見 [alerts.md](../alerts.md) |
| 條件 | 近 5 分鐘 5xx ÷ 全部請求 > 5%（5 分鐘至少 20 個請求才評估），持續 5 分鐘 |

## 代表什麼

不分是哪支 API，整體有超過 5% 的請求回 5xx，而且持續了 5 分鐘。正常時行程 API 不會回 5xx（實測 0 個），
app 只在「後面的東西出事」時回 502／503：資料庫、模型、熔斷。所以這條幾乎都是**某個依賴壞了**。

## 對使用者的影響

看是哪支 API：

- `/api/calendar/events…` 的 5xx → 行程看不到、存不進去
- `/api/calendar/assistant` 的 502／503 → 只有 AI 助理不能用，行程照常
- `/api/auth/…` → 登不進去

## 怎麼確認

1. **哪支 API、哪個狀態碼**（Explore → Mimir）：

   ```promql
   sum by (uri, status) (increase(http_server_requests_seconds_count{job="talkcal-app", status=~"5.."}[5m]))
   ```

2. **對應的 log**（Explore → Loki）：

   ```logql
   {service="app"} | json | http_response_status_code >= 500
   {service="app"} | json | log_level="ERROR"
   ```

   展開一行 →「到 Tempo 看這個請求」，看是哪一段 span 失敗。

3. 同時有沒有其他告警在響：資料庫連不上、連線池、熔斷 —— 有的話直接照那份 runbook，原因已經找到了。

## 可能的原因（由常見到少見）

### 1. AI 助理的模型出事（`/api/calendar/assistant` 回 502／503）

- 503 `MODEL_UNAVAILABLE`：熔斷打開 → [talkcal-model-circuit-open](talkcal-model-circuit-open.md)
- 503 `MODEL_BUSY`：同時等模型的人太多（bulkhead 滿）→ 看 llama-cpp dashboard 的排隊數；短暫的尖峰會自己退
- 502 `ASSISTANT_FAILED`：叫了模型但失敗。log 有原因，常見是模型 KV cache 滿、對話太長

### 2. 資料庫出事（所有 API 一起 5xx）

→ [talkcal-db-down](talkcal-db-down.md) 或 [talkcal-db-pool-timeout](talkcal-db-pool-timeout.md)

### 3. 剛部署的新版有 bug（集中在某一支 API，時間點跟部署對得上）

```bash
cd /opt/talkcal/repo && git log --oneline -3          # 雲端：現在跑的是哪一版
```

先退回上一版止血，bug 在 PR 裡修：

```bash
sudo GIT_REF=<上一個 commit> bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
```

### 4. 登入的外部服務（Google、GitHub）壞了（集中在 `/api/auth/oauth2/…`）

log 有 `Google／GitHub 登入失敗`。看對方的狀態頁；帳密登入照常可用。回呼網址對不上（Elastic IP 換了）時用 `terraform output oauth_callback_urls` 重新登記。

## 怎麼確認已經恢復

- 步驟 1 的查詢回到 0；5 分鐘的視窗滑過去後告警解除、收到 `[RESOLVED]`（最多約 10 分鐘）
- **talkcal — SLO** 儀表板看這次燒掉多少 budget

## 什麼時候往上升級

- 退回上一版之後還是 5xx → 不是程式的問題，看依賴（資料庫、模型、AWS）
- 超過 30 分鐘、而且是行程 API → 照 [README](README.md#升級) 先止血

## 事後要不要寫 postmortem

- 行程 API 或登入受影響：**要**
- 只有助理、而且是供應商的事件：在對應 runbook 的紀錄補一行

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-17 | 驗證（#152）：假 app 20% 回 500 | 恢復後約 4 分鐘解除 | 見 PR #152 |
