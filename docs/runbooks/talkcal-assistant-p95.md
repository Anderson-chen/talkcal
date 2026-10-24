# talkcal AI 助理 p95 延遲超過 30 秒（`talkcal-assistant-p95`）

| | |
|---|---|
| severity | warning —— 上班時間處理 |
| 規則 | [`rules.yaml`](../../ops/grafana/provisioning/alerting/rules.yaml) 第 4 條，總表見 [alerts.md](../alerts.md) |
| 條件 | `/api/calendar/assistant` 近 5 分鐘的 p95 > 30 秒，持續 10 分鐘 |

## 代表什麼

跟助理說一句話，最慢的那 5% 要等超過 30 秒。平常 p95 約 3～4 秒；同時 8 個人時約 9 秒。
到 30 秒代表模型那頭**塞住了，但還沒壞到失敗**（失敗會讓熔斷打開，那是另一條）。

## 對使用者的影響

助理回得很慢，多數人等不到就放棄了。行程功能不受影響。

## 怎麼確認

1. **模型那頭在排隊嗎**（本機）：llama-cpp dashboard 的 `requests_deferred`（排隊中）、`requests_processing`；生成速度 `predicted_tokens_seconds` 有沒有掉
2. **同時有多少人在等**：talkcal-app dashboard 的「同時在等模型的數量」（bulkhead）
3. **慢的那幾個在做什麼**：Tempo 搜 `{ resource.service.name = "talkcal" && name =~ ".*assistant.*" && duration > 30s }`，
   看一個請求叫了模型幾次、每次多久 —— 一次很久是模型慢；叫很多次是 agent loop 在打轉

## 可能的原因（由常見到少見）

### 1. 同時用的人太多（本機的 llama-server 容量只有約同時 2 人）

排隊數很高、每一次叫模型都正常、只是在排。SLO 文件量過：吞吐量在 2 人時就到頂，之後只是排隊。
短時間沒辦法加容量；確認不是異常流量（同一個人狂送），nginx 對 AI 發問有限流。

### 2. 模型生成特別長（thinking 很長、對話很長）

trace 裡單次叫模型就要十幾秒，`predicted_tokens` 很多。看 log 是哪個對話；對話太長時會被 400 擋（不算模型壞）。

### 3.（雲端）Bedrock 變慢

trace 裡 Bedrock 那段很久。看 AWS Health Dashboard；沒辦法修，等。

### 4. GPU 被別的程式佔走（本機）

`nvidia-smi` 看是不是有別的程式在用 GPU、記憶體不夠讓模型掉到 CPU。

## 怎麼確認已經恢復

- p95 回到 10 秒以內；告警在 p95 低於 30 秒的那次評估解除、收到 `[RESOLVED]`
- SLO 的 `assistant-latency` burn rate 降下來

## 什麼時候往上升級

- 慢到開始失敗 → 熔斷會打開，換照 [talkcal-model-circuit-open](talkcal-model-circuit-open.md)
- 長期就是容量不夠 → 開 issue 評估（雲端改 Bedrock 的模型、本機 `-np` 的設定）

## 事後要不要寫 postmortem

不必，除非持續超過一小時或 30 天內第二次。在下面紀錄一行。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
