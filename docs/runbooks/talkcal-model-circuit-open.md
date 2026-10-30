# talkcal 模型熔斷持續打開（`talkcal-model-circuit-open`）

| | |
|---|---|
| severity | warning —— 上班時間處理；SLO 快燒（`assistant-availability`）同時響的話照 critical 處理 |
| 規則 | [`respond.yaml`](../../ops/grafana/provisioning/alerting/respond.yaml) 第 3 條，總表見 [alerts.md](../alerts.md) |
| 條件 | 近 5 分鐘熔斷擋下了請求（`resilience4j_circuitbreaker_not_permitted_calls_total` 有增加），**而且**這 5 分鐘一次都沒關回來（`state="closed"` 一直是 0） |

## 代表什麼

app 叫模型（本機 llama-server、雲端 Bedrock）最近 10 次裡有一半以上失敗或超過 30 秒，熔斷打開（#44）。
打開後 30 秒放 2 個請求去試，試失敗又打開 —— 這條響代表**至少 5 分鐘都沒試成功**，而且這段時間有使用者被擋下。

## 對使用者的影響

- **AI 助理不能用**：送出去馬上拿到 503 `MODEL_UNAVAILABLE`，畫面說「AI 助理暫時無法使用，行事曆的其他功能照常可用」，並倒數 `Retry-After`（#43）
- **行程的增刪改查、登入照常**（#44 演練過：模型斷線、卡住時行程 API 全部 200）
- 使用者打的字不會不見，倒數完可以按「再試一次」

## 怎麼確認

1. **talkcal-app dashboard 最下面「模型熔斷」那一排**：
   - 熔斷狀態（state timeline）：一直在 open／half_open，沒有回到 closed
   - 沒叫模型就擋下的請求：在增加
   - 失敗率／慢呼叫率：哪一個超過 50%？**失敗率高 = 模型連不上或回錯誤；慢呼叫率高 = 模型卡住**
   - 失敗率是 **-1** 代表最近不到 5 次呼叫、還不夠判斷，不是異常（正常時沒人用助理就會是 -1）
2. 查詢：

   ```promql
   resilience4j_circuitbreaker_state{job="talkcal-app", name="chat-model"}
   resilience4j_circuitbreaker_failure_rate{job="talkcal-app", name="chat-model"}
   resilience4j_circuitbreaker_slow_call_rate{job="talkcal-app", name="chat-model"}
   ```

3. app 的 log（Loki）：

   ```logql
   {service="app"} |= "模型的熔斷狀態改變"        # 什麼時候打開、半開、關回來
   {service="app"} |= "AI 助理沒有叫模型"          # 被擋下的請求（503，code=MODEL_UNAVAILABLE）
   {service="app"} | json | log_level="ERROR"      # 叫模型失敗的那幾筆
   ```

   ERROR 的 message 只寫「AI 助理失敗：Request failed」，**原因在 stack trace 最後的 `Caused by`**（展開那一行看 `error.stack_trace`）：

   | `Caused by` | 原因 |
   |---|---|
   | `UnknownHostException: llm-chat`、`ConnectException: Connection refused` | 模型容器不在或沒在聽 → 原因 1 |
   | `HttpTimeoutException`、`SocketTimeoutException` | 模型卡住 → 原因 2 |
   | 帶 `429`、`ThrottlingException` | Bedrock 限流 → 原因 3 |
   | 帶 `401`、`403` | key 過期或權限 → 原因 4 |
   | 帶 `404` | 模型名稱錯 → 原因 5 |

## 可能的原因（由常見到少見）

先看你在哪個環境：本機是 llama-server（`llm-chat` 容器），雲端是 Bedrock。

### 1.（本機）llm-chat 容器停了或還在載入模型

```bash
docker compose ps llm-chat                  # Exited？unhealthy（還在載入）？
docker compose logs --since 15m llm-chat | tail -30
docker compose up -d --wait llm-chat        # 起來；載入 5GB 的模型要一兩分鐘，healthy 才算好
```

### 2.（本機）llm-chat 卡住（GPU 記憶體滿、KV cache 滿、排隊太長）

慢呼叫率高、失敗率不高。看 **llama-cpp dashboard** 的 `requests_deferred`（排隊數）、`requests_processing`。

```bash
docker compose restart llm-chat             # 卡住時最快的解法；會中斷正在生成的請求（它們本來也在等逾時）
nvidia-smi                                  # GPU 記憶體是不是被別的程式吃掉
```

### 3.（雲端）Bedrock 被限流或暫時不能用

app 的 ERROR log 有 `429`、`ThrottlingException`、`ServiceUnavailable`。

- 開 [AWS Health Dashboard](https://health.aws.amazon.com/health/status) 看 Bedrock 在 ap-northeast-1 有沒有事件
- 被限流的話，Service Quotas → Amazon Bedrock 看這個模型的每分鐘 token／請求上限；短時間內沒辦法調高，等它恢復

### 4.（雲端）Bedrock API key 過期或權限錯

ERROR log 有 `401`、`403`、`AccessDenied`。key 預設 90 天到期（`bedrock_key_days`）。在本機 `infra/envs/1-ec2/` 換一把新的，再到機器上重新部署：

```bash
terraform apply -replace='aws_iam_service_specific_credential.bedrock[0]'
# 連上機器之後
sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
```

### 5.（雲端）模型名稱錯或模型下架

ERROR log 有 `404`、`ValidationException ... model identifier is invalid`。檢查 `bedrock_model`（`variables.tf`）是不是 ap-northeast-1 有的模型 ID，改了再 apply、重新部署。

## 怎麼確認已經恢復

- 熔斷狀態回到 **closed**（第一個 half_open 的試探成功就會關回來；log 會有一行 `State transition from HALF_OPEN to CLOSED`）。沒人在用的時候它會一直停在 open／half_open 等試探 —— 自己送一句話給助理就會觸發
- 告警解除、收到 `[RESOLVED]`（closed 出現後下一次評估就解除）
- 助理回得了話；`assistant-latency` 沒有因為剛恢復而一直超過 8 秒

## 什麼時候往上升級

- 本機重啟 llm-chat 後 10 分鐘還是一直失敗 → 看是不是模型檔壞了（`model-seed` 的 SHA256 驗證）
- 雲端 Bedrock 是 AWS 那邊的事件 → 沒辦法修，等；前端已經會說明、行程照常，不必做別的
- 熔斷打開但模型看起來好好的（自己 curl 模型會回）→ 可能是 app 這邊的問題（逾時設定、網路），開 issue 查，不要只重啟

## 事後要不要寫 postmortem

- 原因 4、5（key 過期、設定錯）：**要**，這是可以預先發現的（例如 key 到期前提醒）
- 雲端供應商的事件：不必寫完整的，在下面紀錄一行時間和影響
- 同一個原因 30 天內第二次：**要**

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-20 | 演練：`docker stop llm-chat`（原因 1） | 照 runbook 1 分 17 秒 | [演練紀錄](../drills/2026-10-20-runbook-drills.md)；補了 `Caused by` 對照表、失敗率 -1 的意思 |
