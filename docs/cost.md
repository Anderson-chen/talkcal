# 成本：每個請求多少錢，自架 GPU 和 Bedrock 在哪裡交叉

（#35，2026-10-17 算。單價、token 數變了就照最下面「重算」那一節換數字。）

**結論**：機器開著的每一小時裡，AI 助理的請求**少於約 1,341 個**（平均 0.37 req/s）時，Bedrock 比自己開 GPU 機器便宜。
換成一天 24 小時都開著，就是**一天約 32,000 個請求**。現在是展示用的量（一天幾十到幾百個），差了兩個數量級，所以雲端走 Bedrock。

## 1. 一個請求燒多少 token（量的，不是猜的）

AI 助理一個請求（`POST /api/calendar/assistant`）是一段 agent loop：會叫模型好幾次，每次都把 system prompt、工具定義、對話記憶整包再送一次，所以 input 遠多於 output。

| 量什麼 | 數字 | 怎麼量的 |
|---|---|---|
| 每個請求叫模型幾次 | 2.4 次 | 30 個請求叫了 72 次 |
| 每個請求的 input token | **3,905** | 117,159 ÷ 30 |
| 每個請求的 output token | **103** | 3,096 ÷ 30 |

- 量法：[`perf/tokens.js`](../perf/tokens.js)，1 個使用者把訊息池（查詢、提議行程各半）每句說 3 輪，前後各讀一次 app 的 `/actuator/prometheus`，相減。
- 數字來源是 app 自己的 `gen_ai_client_token_usage_total`：Spring AI 每叫一次模型就把回應裡的 `usage` 記進去（`ObservationConfiguration` 多貼一個 `model_backend` 標籤，見下面第 4 節）。跟 Grafana 成本 dashboard 用的是同一個指標。
- 2026-10-17 本機 llama-server（Qwen3-8B Q4_K_M）。對 bootRun（8090）跑 3 輪、對 docker 的 app（18090）跑 1 輪，每個請求的平均都是 3,905 / 103。
- **假設**：雲端的 Qwen3 32B 跟本機的 8B 同一家族、同一套 tokenizer，同一句話送出去的 input token 數相同；output 會不一樣（模型不同，回答長度不同）。output 只佔費用的一成，影響小，見第 3 節的敏感度。

## 2. 單價（2026 年 10 月，官方來源）

| 項目 | 單價 | 來源 |
|---|---|---|
| Bedrock Qwen3 32B input（東京，`bedrock-mantle`，Standard） | **0.18 USD / 1M token** | AWS Price List，`AmazonBedrock` 東京，publicationDate 2026-10-16，usagetype `APN1-qwen.qwen3-32b-mantle-input-tokens-standard` |
| Bedrock Qwen3 32B output（同上） | **0.73 USD / 1M token** | 同上，`...-output-tokens-standard` |
| EC2 g6.xlarge（L4 24GB，東京，Linux 隨需） | **1.1672 USD / 小時** | AWS 定價頁的資料檔（`ec2-ondemand-without-sec-sel`，東京，Linux），發布 2026-10-15 |
| EC2 m7i-flex.large（東京，Linux 隨需） | **0.12369 USD / 小時** | 同上 |

兩種部署方式就是 [infra/envs/1-ec2](../infra/envs/1-ec2/README.md) 的 `model_backend`：

- `bedrock`：m7i-flex.large 跑 app、資料庫、觀測，模型叫 Bedrock，**按 token 付錢**。
- `gpu`：g6.xlarge 全部自己跑（模型是 Qwen3-8B），**按小時付錢，跟 token 數無關**。

Bedrock 東京沒有 Qwen3 8B，所以兩邊跑的不是同一顆模型（32B vs 8B）。這裡比的是「現在 repo 裡兩種部署實際的帳單」，不是同一顆模型的兩種託管方式。

## 3. 交叉點

每個請求的 token 費用（Bedrock）：

```
3,905 × 0.18 / 1M + 103 × 0.73 / 1M
= 0.00070290 + 0.00007519
= 0.00077809 USD          （每 1,000 個請求 0.78 USD）
```

機器開著的每小時，GPU 機器比一般機器多付：

```
1.1672 − 0.12369 = 1.04351 USD / 小時
```

這段差價夠付多少個請求的 token：

```
1.04351 ÷ 0.00077809 ≈ 1,341 個請求 / 小時  ≈ 0.37 req/s
```

**交叉點是一個「速率」**，不是一天的總量：機器只在展示時開機，開著的那段時間裡速率超過它，自己跑才划算。換成一天的量：

| 機器一天開多久 | GPU 機器一天 | m7i-flex 一天 | 交叉點（一天的請求數） |
|---|---|---|---|
| 24 小時 | 28.01 USD | 2.97 USD | **≈ 32,187** |
| 8 小時（展示日） | 9.34 USD | 0.99 USD | ≈ 10,729 |

對照現在的量：一天 100 個請求，Bedrock 的 token 費一個月約 2.3 USD；同一個月 g6.xlarge 全天開著要 840 USD。

**敏感度**（24 小時開機）：output 是最不確定的一項（換模型、開 thinking 都會變長）。

| 每個請求的 output token | 每個請求 | 交叉點（一天） |
|---|---|---|
| 103（量到的） | 0.00078 USD | 32,187 |
| 500 | 0.00107 USD | 23,452 |
| 1,000 | 0.00143 USD | 17,478 |

input 佔九成費用：system prompt 或工具定義變長、對話記憶留得更多，交叉點會跟著往下掉。dashboard 上「每個請求平均 token」那張圖就是在看這件事。

### 沒算進來的

- **容量**：交叉點之上，一台 L4 撐不撐得住還沒量過（GPU 配額還沒下來）。用 Little's Law 粗估：0.37 req/s × 本機量到的平均 2.4 秒 ≈ 同時 0.9 個請求在跑，一台應該夠；但尖峰是平均的好幾倍，要在 g6.xlarge 上重跑 `perf/knee.js` 才知道。撐不住就要兩台，交叉點跟著翻倍。
- **折扣**：Savings Plans、Spot 會讓 GPU 的時薪變低；Bedrock 也有 Flex（東京 Qwen3 32B 是 Standard 的一半）。這裡都用隨需、Standard。
- **其他費用**：EBS、流量、CloudWatch 兩邊差不多，相減就消掉了。自己跑模型的維運時間（升級 llama.cpp、換模型檔）沒有換算成錢。
- **品質**：32B 和 8B 答得不一樣好。成本只是選型的一半，另一半是 `./gradlew eval` 的分數。

## 4. 怎麼在 Grafana 上看

儀表板 **`talkcal — 成本（token 與費用）`**（[`ops/grafana/provisioning/dashboards/talkcal-cost.json`](../ops/grafana/provisioning/dashboards/talkcal-cost.json)）：

| 面板 | 看什麼 |
|---|---|
| token/s，依種類和 model_backend | 燒得多快；`bedrock` 的才收錢 |
| 每個請求平均 token | 跟第 1 節的 3,905 / 103 比，偏離了就要重算 |
| 每個請求叫幾次模型 | prompt 改壞、模型繞圈時會變大，token 跟著倍增 |
| 每次叫模型的平均 token | 掉到 0 = 模型沒回 `usage`，費用會悄悄少算（見下） |
| 每個請求的費用 | 「實際」只算 Bedrock；「如果全走 Bedrock」把本機的用量也換算成錢 |
| 請求/小時 | 超過 1,341 變橘色：到了該考慮自己跑的量 |

單價是 dashboard 上方的兩個變數，換模型或換區域時直接改。

**為什麼要多一個 `model_backend` 標籤**：本機 llama-server 和雲端 Bedrock 都是用 OpenAI 的 client 打的，Spring AI 內建的 provider 標籤（`gen_ai_system`）兩邊都是 `openai`，分不出哪些 token 要收錢。模型名稱分得出「哪一顆」，但收不收錢取決於「在哪裡跑」，所以另外從設定 `talkcal.ai.backend` 貼（預設 `llama-cpp`，`compose.bedrock.yaml` 蓋成 `bedrock`）。

**usage 斷掉時不會報錯**：模型的回應沒帶 `usage` 時，Spring AI 記成 0 個 token 而不是跳過（`ModelTokenUsageMetricsTest` 驗過）。哪天改成串流卻沒開 `include_usage`，dashboard 上的費用會變少，看起來像省錢。所以「每次叫模型的平均 token」掉到 0 時顯示紅色。

## 重算

1. **token 數**：app 跑起來之後
   ```bash
   ROUNDS=3 k6 run perf/tokens.js
   ```
   最後印出每個請求平均 input、output 和換算的費用。單價用 `PRICE_IN`、`PRICE_OUT` 覆蓋。
2. **Bedrock 單價**（公開的 AWS Price List，不需要帳號）：
   ```bash
   curl -s https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/ap-northeast-1/index.json -o bedrock.json
   python -c "import json;d=json.load(open('bedrock.json'));[print(p['attributes']['usagetype'],pd['pricePerUnit']['USD']) for s,p in d['products'].items() if 'qwen3-32b-mantle' in p['attributes'].get('usagetype','') and p['attributes']['usagetype'].endswith('standard') for t in d['terms']['OnDemand'][s].values() for pd in t['priceDimensions'].values()]"
   ```
   印的是每 1K token 的價錢，乘 1000 就是每 1M。
3. **EC2 時薪**（AWS 定價頁用的資料檔，gzip 壓過）：
   ```bash
   curl -s "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/Asia%20Pacific%20(Tokyo)/Linux/index.json" -o ec2.json.gz
   python -c "import json,gzip;d=json.loads(gzip.open('ec2.json.gz').read());[print(v['Instance Type'],v['price']) for r in d['regions'].values() for v in r.values() if v['Instance Type'] in ('g6.xlarge','m7i-flex.large')]"
   ```
4. **交叉點** = (GPU 時薪 − 一般機器時薪) ÷ 每個請求的費用，單位是「每小時的請求數」。
   改了的話，dashboard「請求/小時」的橘色門檻（1341）和面板說明也要跟著改。
