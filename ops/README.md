# ops —— 觀測環境（Observability）

用 Docker 一次拉起 **Alloy + Mimir + Loki + Tempo + Grafana**，跟被觀測的服務分開。
被觀測的是 `deploy/` 那組容器（app、模型、資料庫、前端），以及本機開發時在 `backend/` 裡用 `./gradlew bootRun` 跑的 app。

## 整條流程

每種訊號都走「產生 → 收集 → 儲存 → 查詢/畫圖」四段。收集器只有一個，資料庫一種訊號一個：

```
              產生                          收集             儲存          查詢/畫圖
指標 metrics  Micrometer → /actuator/prometheus ◀─抓(15s)─┐
              llama-server --metrics → /metrics ◀─抓──────┤
              k6（壓測時）→ OTLP ─────────────推──────────┤
                                                          ├ Alloy ─▶ Mimir ─┐
日誌 logs     SLF4J/Logback → stdout(ECS JSON) ◀─讀───────┤       ─▶ Loki  ─┼─▶ Grafana
                                                          │       ─▶ Tempo ─┘
追蹤 traces   Micrometer Tracing → OTLP ──────推──────────┘
```

| 訊號 | 一筆資料長什麼樣 | 回答的問題 | 收集方式 | 存在 | 查詢語言 |
|------|------------------|------------|----------|------|----------|
| 指標 | 標籤 + 一個數字 | 有沒有問題、多嚴重（p95 變慢了） | Alloy **拉**（k6 例外，由它**推**） | Mimir | PromQL |
| 日誌 | 一行文字（JSON） | 發生了什麼事（那個請求問了什麼） | Alloy **讀** stdout | Loki | LogQL |
| 追蹤 | 一棵 span 樹 | 時間花在哪一段（助理叫了幾次模型、各多久） | app **推**給 Alloy | Tempo | TraceQL |

## 五個角色

| 服務 | 做什麼 | 對外埠 | 看它 |
|------|--------|--------|------|
| **Alloy** | 唯一的收集器：抓指標、讀容器 log、接 app 推來的 trace 和 k6 推來的指標，分送給下面三個 | 12345、4318 | <http://localhost:12345>（管線圖）|
| **Mimir** | 存**指標**，講 Prometheus 的查詢 API（PromQL） | 9009（只開本機） | 靠 Grafana 查 |
| **Loki** | 存**日誌**，只替標籤建索引 | 3100 | 靠 Grafana 查 |
| **Tempo** | 存**追蹤**，靠 trace id 找 | 不對外 | 靠 Grafana 查 |
| **Grafana** | 畫圖前台，自己不存資料；三種訊號互相跳轉 | 3000 | <http://localhost:3000> |

Grafana 一啟動就自動接好 Mimir、Loki、Tempo 三個資料源和它們之間的跳轉（見 `grafana/provisioning/`），
開網頁不必登入，直接是 Admin（純本機學習用，刻意關掉登入）。

## 選型理由

### 為什麼收集器只有 Alloy 一個

- **找來源、加標籤、送出去是同一種工作**：三種訊號用同一套標籤（`service`、`env`），換儲存只改 Alloy 的出口，
  app 和被抓的服務都不用動。
- **不用 Promtail**：Loki 舊的 log 收集器，官方已停止開發。
- **不讓 app 直接送 Tempo**：app 只認得 OTLP 這個標準協定、只知道 Alloy 在哪。之後 Tempo 換別家，app 不必重新部署。
- **代價：Alloy 是單點**。但兩種訊號斷掉的後果不同——
  - log 只會**延遲**：Docker 照樣把 stdout 寫進自己的 log 檔，Alloy 回來後依位置檔接著讀。
  - 指標會**缺一段**：拉取只拿得到「當下」的值，Alloy 停了的那段時間補不回來。
  - 單機上多起幾台 Alloy 也救不了「整台機器掛掉」，所以目前只靠 `restart: unless-stopped` 自動拉回來。
    正式環境的做法見下面「下一步」。

### 為什麼存指標用 Mimir 而不是 Prometheus

Prometheus 其實是「抓 + 存」兩份工作。抓的工作交給 Alloy 之後，剩下的只有「存」。
Mimir 存的就是 Prometheus 格式的資料、查詢也用 PromQL——對 Grafana 來說它就是「一台很大的 Prometheus」，
所以儀表板的查詢一個字都沒改，資料源 type 也仍然是 `prometheus`。

選 Mimir 不是因為現在的量需要它，而是為了學正式環境的形狀：

| 需求 | Prometheus | Mimir |
|------|-----------|-------|
| 保存好幾年 | 硬碟會撐爆 | 放物件儲存（S3），便宜又不用管容量 |
| 一台掛掉 | 那段資料沒了 | 預設寫 3 份，掛一台不掉 |
| 很多團隊共用 | 每團隊各架一台 | 一套系統用租戶隔開 |
| 兩台收集器互為備援 | 收到兩份就存兩份 | HA tracker 去重，只留一份 |

**為什麼比較重**：Mimir 是給上面那些需求設計的分散式系統，拆成 distributor、ingester、querier、
store-gateway、compactor 等元件。單機用 `-target=all` 把它們塞進同一個程序，
`mimir/mimir.yaml` 大半都在把分散式功能關掉（多租戶、複製、物件儲存）——跟 `loki/loki-config.yaml` 是同一個思路，
因為 Mimir、Loki、Tempo 是 Grafana 用同一套骨架做出來的。

### 為什麼 trace 要另外一個資料庫（Tempo）

trace 是一棵 span 樹，每個 span 有自己的 id 和父 span，靠 trace id 串成一個請求。
Mimir 只放得下「標籤 + 一個數字」，Loki 放的是一行一行文字，都裝不下這種樹。

## 怎麼跑

```bash
cd deploy && docker compose up -d   # 先起被觀測的那組：它會建立 Alloy 要加入的網路
cd ops && docker compose up -d      # 再起這組
docker compose ps                   # 看狀態
docker compose logs -f              # 追 log（Ctrl+C 離開，不會關容器）
docker compose down                 # 關掉（資料留著）
docker compose down -v              # 關掉並清空資料，整組重來
```

Alloy 加入了 deploy/ 的網路（`talkcal-deploy_default`）：用服務名稱抓指標，app 也用 `alloy:4318` 送 trace。
代價是**要先起 deploy/ 再起 ops/**，網路不存在時 Alloy 起不來。

## 指標：Alloy 抓誰、送去哪

| job | 位址 | 是誰 |
|-----|------|------|
| `talkcal-app` | `app:8091/actuator/prometheus` | deploy/ 的 app 容器（management 埠，不發佈到主機） |
| `llama-cpp` | `llm-chat:8080/metrics` | Qwen3-8B（deploy/ 的 llm-chat） |

每 15 秒抓一次，用 `remote_write` 推進 Mimir。本機 bootRun 的 app 不在 deploy/ 的網路裡，不會被抓。

抓不抓得到，看 <http://localhost:12345> 的 `prometheus.scrape.*` 元件，或在 Grafana 查 `up`（1 = 抓得到）。
想不透過 Grafana 直接查：

```bash
curl 'localhost:9009/prometheus/api/v1/query?query=up'
```

app 打給模型的呼叫，原本有一組 `http_client_requests_seconds`（RestClient 自動產生）。
改用 Spring AI 之後沒有了：Spring AI 的 OpenAI 模組底層是官方 openai-java SDK（OkHttp），不走 RestClient。
取而代之的是 Spring AI 自己的觀測（`gen_ai.*` 系列，含 token 用量），見下面「追蹤」一節。

## 日誌：Loki 收什麼

Alloy 透過 Docker 的 API（`docker.sock`）自動發現 `deploy/` 那組容器（compose 專案 `talkcal-deploy`），
讀它們的 stdout 送進 Loki。新容器起來不必改設定；其他專案的容器、ops 自己的容器都不收。
管線怎麼接、為什麼這樣選標籤，見 `alloy/config.alloy` 的註解。

每筆 log 帶的標籤：

| 標籤 | 例子 | 用途 |
|------|------|------|
| `service` | `app`、`llm-chat`、`postgres` | compose 服務名，最穩，查詢主要靠它 |
| `container` | `talkcal-app` | 容器名 |
| `env` | `docker` | 環境。之後別的環境的 log 也送進來時用它分開 |

trace id **不是**標籤（每個請求都不同，放進標籤會讓索引爆掉），它在 log 內容的 `traceId` 欄位裡。

本機開發的 bootRun 是跑在 Windows 上的程序、不在 Docker 裡，它的 log **不會**進 Loki，照常看終端機。

### 在 Grafana 查

左側 **Explore** → 資料源選 **Loki** → 切到 **Code** 模式貼查詢：

```logql
# deploy/ 的服務一起看，照時間排——跟助理說一句話，會看到 app 的 access log 和 llm-chat 的一次或好幾次生成
{env="docker"}

# 只看生成模型每次請求的耗時
{service="llm-chat"} |= "total time"

# app 的 log 是 JSON（docker profile 開的），| json 把欄位拆開，就能照等級篩
{service="app"} | json | log_level=~"WARN|ERROR"

# app 的 access log：每個請求一行，帶方法、路徑、請求與回應的 body、狀態碼、耗時、traceId（AccessLogFilter 寫的）
{service="app"} | json | log_logger="access"

# 只看失敗的請求，連同當時送了什麼、回了什麼
{service="app"} | json | log_logger="access" | http_response_status_code >= 400

# 超過 5 秒的慢請求（event_duration 是奈秒）
{service="app"} | json | log_logger="access" | event_duration > 5000000000

# 每 5 分鐘有幾筆 WARN/ERROR——從 log 算出數字，可以畫成圖
sum by (service) (count_over_time({env="docker"} | json | log_level=~"WARN|ERROR" [5m]))
```

## 追蹤：一個請求的時間花在哪

app 對每個請求開一個 trace、全部送出（`management.tracing.sampling.probability=1.0`），
用 OTLP 推給 Alloy 的 4318，Alloy 決定留哪些（見下面「Trace 取樣」）再轉給 Tempo。

> **改用 Spring AI 之後，trace 的樹還沒重新量過。** 以前往外的 span 是 RestClient 產生的 HTTP span
> （`http post /v1/chat/completions`）；現在跟模型講話的是 Spring AI，
> 底層是官方 openai-java SDK（OkHttp），**不會**產生 HTTP span，請求也不再帶 `traceparent` 標頭。
> 換成 Spring AI 自己的觀測：AI 助理直接用 ChatModel，每叫一次模型一個 span，帶 `gen_ai.*` 屬性（模型名稱、token 用量）。
> 部署後跟助理說一句話，到 Tempo 看實際的 span 名稱，再回來補一張 `http post /api/calendar/assistant` 的樹。
> `talkcal — Traces (Tempo)` 儀表板裡用 `kind=client` 篩往外呼叫的那幾張圖，到時也要跟著改。

| span | 誰量的 | 為什麼是它 |
|------|--------|------------|
| 根 span（進來的請求） | Spring Boot 自動註冊的 filter | 進來的 HTTP 由 Spring MVC 處理，它管得到，不必寫任何設定 |
| 跟模型的往返 | Spring AI（ChatModel 內建的 Micrometer Observation） | 不必寫觀測程式碼；比 HTTP span 多了 token 用量，少了 HTTP 狀態碼 |

曾經用 AOP 在 outbound port 外面多包一層 span（名稱是 `retrievePassages`、`generateReply`），
後來拿掉了。Spring AI 自己的 span 本來就是照「叫一次模型」這種動作切的，更用不著了（實際名稱同樣等部署後確認）。
llama.cpp 本身不產生 span，所以樹只到 app 呼叫出去的那一層。

想在 trace 上看到送給模型的完整 prompt（下一步第 1 項），Spring AI 有現成的開關：
`spring.ai.chat.client.observations.log-prompt=true`。prompt 裡可能有使用者的私人內容，所以預設關著，要開再想清楚。

在 Grafana 看 trace 有兩個地方：

- **儀表板 `talkcal — Traces (Tempo)`**：全部用 TraceQL 查 Tempo。上排是從 trace 算出來的數字（助理請求速率、
  各段的 p95、沒成功的 span 數），下排是請求清單（最近的、超過 5 秒的、沒成功的），點 Trace ID 就打開瀑布圖。
  查詢只挑 `/api/calendar/assistant`，只看跟助理的對話。（actuator 的抓取與健康檢查走 management 埠 8091，那個埠上的請求本來就不產生 trace。）
- **Explore** → 資料源選 **Tempo** → **Search** 分頁，Service Name 選 `talkcal`：自己下條件找。

儀表板上排用的是 **TraceQL metrics**（`| rate()`、`| quantile_over_time()`）：Tempo 直接從存下來的 span 算出時間序列，
不必另外開 metrics-generator 把數字寫進 Mimir。代價是分位數用 2 的次方分桶估（0.5s、1s、2s…），
只適合看「慢在哪一段」的量級，精準的 p95 看 talkcal-app 儀表板（指標那條線）。

本機 bootRun 的 app 也會送 trace（走主機的 `127.0.0.1:4318`），只要 ops/ 有起來。
ops/ 沒起時 trace 送不出去，app 只會在 log 印 warning，請求照常處理。

### Trace 取樣：錯的、慢的全留，其餘抽樣（#37）

| 環境 | 一般請求留幾 % | 等多久才決定 | 設在哪 |
|------|---------------|--------------|--------|
| 本機 | 100 | 5 秒 | `alloy/config.alloy` 的預設值（不設環境變數） |
| 雲端（EC2） | 10 | 125 秒 | [`infra/envs/1-ec2/compose.ops.yaml`](../infra/envs/1-ec2/compose.ops.yaml)，`deploy.sh` 疊上去 |

不論比例多少，下面三種**一律留**：span 狀態是 ERROR、回 5xx（`outcome=SERVER_ERROR`）、整個 trace 超過 5 秒。

**為什麼是 tail（在 Alloy）而不是 head（在 app）**

| | head：app 端 `management.tracing.sampling.probability=0.1` | tail：Alloy 的 `otelcol.processor.tail_sampling` |
|---|---|---|
| 什麼時候決定 | 請求一進來就擲骰子 | 一個 trace 的 span 到齊後看整棵樹 |
| 錯誤、慢請求 | 跟一般請求一樣只留 10%——最想看的那幾筆九成找不到 | 依規則全留 |
| 省到哪 | app 少產生 span、少送網路 | app 照樣全送（同一台機器的內部網路，便宜），省的是 Tempo 的儲存和查詢 |
| 代價 | 幾乎沒有 | Alloy 記憶體要放「還沒決定」的 trace；一個 trace 的 span 必須進同一個 Alloy |

這個專案的 trace 成本在 Tempo 那頭（EC2 的磁碟、查詢），app 送出去的那段是同機器的內部網路；
而 #37 的目標「關鍵請求留得住」head 做不到，所以選 tail。代價那欄目前都不是問題：只有一個 Alloy，
一般流量下等決定的 trace 只有幾千個。哪天 Alloy 要變成多個，前面得加一層照 trace id 分流的（`otelcol.exporter.loadbalancing`）。

app 端因此**必須**維持 1.0：app 先丟掉的 trace，Alloy 根本看不到，錯誤再多也撈不回來。

**規則怎麼定的**

- **5xx 要另外一條規則**：實測 Spring 的 HTTP span 回 500 時狀態是空的（UNSET），只有 `outcome` 屬性記著 `SERVER_ERROR`。
  只靠「狀態是 ERROR」會漏掉 500。4xx 不全留：未登入、找不到是使用者那邊的事，量大又不代表系統出問題。
- **慢 = 超過 5 秒**：AI 助理正常一次約 2–3 秒，行程 API 幾十毫秒。等 #34 定了 SLO 改成跟延遲目標對齊。
- **等 125 秒才決定（雲端）**：span 是「結束時」才送，根 span 最後到。等不夠久，慢請求的根 span 還沒到就被決定，
  看起來不慢而被抽掉（下面反例 3）。叫模型的逾時是 2 分鐘，所以等 125 秒。代價是雲端的 trace 晚兩分鐘才查得到。
  本機 100% 怎麼決定都是留，只等 5 秒。

**證據**：另起一組 Alloy（就是這份 `config.alloy`）＋ Tempo，送合成 trace（形狀照 app 的真實 span：
`outcome`、`uri` 屬性、根 span 加一個 jdbc 子 span），再用 TraceQL 數每組有根 span 的 trace 有幾個。

| 組別 | 送幾個 | 雲端設定（10%）留下 | 本機設定（100%）留下 |
|------|------:|------:|------:|
| 一般（40ms、200） | 200 | **19** | 200 |
| 4xx（`CLIENT_ERROR`） | 40 | **6** | 40 |
| 接近門檻（4.5 秒） | 40 | **2** | 40 |
| 慢（6 秒） | 20 | **20** | 20 |
| span 狀態 ERROR | 20 | **20** | 20 |
| 5xx、狀態空的（Spring 真實的樣子） | 20 | **20** | 20 |

反例：

1. **只有「狀態是 ERROR」這條規則會漏 5xx**：Alloy 的 `otelcol_processor_tail_sampling_count_traces_sampled_total`
   上，`errors` 規則只判了 20 個留（狀態 ERROR 那組），5xx 那組 20 個全靠 `server-errors` 規則才留下。
2. **不是什麼都留**：接近門檻的 4.5 秒、4xx 都只按比例留（2/40、6/40），規則沒有寬到把它們全收。
   丟掉的 trace 連子 span 都沒進 Tempo（不帶根 span 的條件查也是 19），沒有留下半截。
3. **等太短會漏慢請求**：模擬慢請求真實的到達順序——子 span 先到、根 span 8 秒後才到。
   等 5 秒：20 個慢請求留下 **0** 個（決定時只看到 5ms 的子 span）；等 20 秒：**20** 個全留。

**對從 trace 算出來的數字的影響**

抽樣之後，Tempo 裡的 trace 不再是全部請求，而且是**有偏的**：錯的、慢的 100% 在，一般的只有 10%。

- **`talkcal — Traces (Tempo)` 儀表板的 TraceQL metrics**（請求速率、各段 p95、失敗 span 數）：
  雲端上速率會少算（約剩一成多），p95 和錯誤比例會**高估**（慢的、錯的都在，快的被抽掉）。
  這幾張圖在雲端只拿來找「哪一段慢」、點進 trace，**數字以 talkcal-app 儀表板為準**——那是 app 的 Micrometer 指標，
  每個請求都算到，跟取樣無關。
- **Tempo metrics-generator（#31）**：它在 Tempo 收到 span 時才算 span metrics／service graph，
  位置在 Alloy 的取樣**之後**，看到的是抽樣後的 trace——service graph 上的請求數會少算、錯誤率會高估，跟上面同一個偏差。
  Tempo 的 `span_multiplier_key` 只適用「每個 span 帶著固定抽樣比例」的 head sampling，tail 的比例依規則而不同，套不上。
  要準的話，把 span metrics／service graph 改在 Alloy 裡、取樣**之前**算（`otelcol.connector.spanmetrics`、
  `otelcol.connector.servicegraph`），再寫進 Mimir。本機 100% 不受影響；雲端要不要搬，看 #31 的圖拿來做什麼。
- **exemplar**：talkcal-app 圖上的 exemplar（指標點掛的 trace id）由 app 決定，可能指到被抽掉的 trace，點進去會找不到。
  通常會去點的是延遲尖峰、錯誤那幾個點——那些剛好是全留的。

## 成本：token 用量和費用

app 每叫一次模型，Spring AI 把回應裡的 `usage` 記成 `gen_ai_client_token_usage_total`（`gen_ai_token_type` 分 input / output / total），
標著模型名稱和 `model_backend`（`llama-cpp` 或 `bedrock`，從設定 `talkcal.ai.backend` 來）。

儀表板 **`talkcal — 成本（token 與費用）`**：token/s、每個請求平均 token、每個請求叫幾次模型、照 Bedrock 單價換算的每個請求費用，
以及請求速率離「自架 GPU 比較划算」的交叉點還有多遠。單價是 dashboard 上方的變數。
算法、單價來源、交叉點怎麼來的見 [docs/cost.md](../docs/cost.md)。

## 壓測：k6 的指標也進 Mimir

k6 跑完就結束，沒有 `/metrics` 可以等 Alloy 來抓，所以跟 trace 一樣由它用 OTLP 推給 Alloy 的 4318，
Alloy 翻譯成 Prometheus 格式再寫進 Mimir。跑法見 [perf/README.md](../perf/README.md)（`perf/run-to-grafana.sh`）。

儀表板 **`talkcal — 壓測（k6）`**：上排是 k6 看到的（VU、請求速率、延遲、失敗率），下排是同一段時間 server 那頭的
（app 和 k6 的 p95 疊在同一張、模型處理中 vs 排隊中、5xx、生成速度）。人數一加，哪一條先動、動多少，對照著看。

部署時間點畫成直線（annotation），k6 和 talkcal-app 兩個儀表板都有：從 Loki 裡 app 自己印的 log 抓，紅線是收到 SIGTERM
（`Commencing graceful shutdown`）、紫線是關機時還有請求沒做完（`Graceful shutdown aborted`）、綠線是新版起好（`Started Application`）。
不另外記部署事件：app 本來就會印這幾行，用它最不會漏。

| 選擇 | 為什麼 |
|------|--------|
| 經過 Alloy，不讓 k6 直接寫 Mimir | 收集器只有一個這條規則不破例；Mimir 也不必為 k6 多開一個對主機的入口 |
| k6 的 `opentelemetry` 輸出，不用 `experimental-prometheus-rw` | 前者在 k6 v2 是正式版，後者還是 experimental；而且跟 trace 共用 4318，不必多開埠 |
| 延遲用 exponential histogram（Mimir 存成 native histogram） | 預設的固定 bucket 最大 10 秒，模型一塞車 p95 就被壓在 10 秒。要 Alloy 的 remote_write 開 `send_native_histograms`，不開會整批被丟掉、不報錯 |
| 名稱加 `k6_` 前綴 | k6 預設不加，會出現 `checks_total`、`vus` 這種跟別的服務分不清的名字 |

驗過準不準：同一次 knee 壓測，Grafana 算出的 p95（792ms → 1572ms → 3360ms）跟 k6 收工印的表一致。

## 三種訊號之間怎麼跳

| 從 | 到 | 怎麼點 | 靠什麼接起來 |
|----|----|--------|--------------|
| 指標（talkcal-app 儀表板的 p95 圖） | trace | 圖上的小菱形（exemplar）→ 點 trace_id | app 吐指標時在 histogram 的點上附 trace id，Mimir 存下來（`max_global_exemplars_per_user`） |
| log（Loki 的 access log） | trace | 展開一行 → **到 Tempo 看這個請求** | Loki 資料源的 derived field 從內容抓出 `traceId` |
| trace（Tempo） | log | span 旁的 **Logs for this span** | Tempo 資料源用 trace id 去 Loki 搜內容 |

## 下一步（一次一件）

1. **app 記錄送給模型的完整 prompt**：access log 已經記了使用者說了什麼（請求 body），
   但還看不到最後組出來送給 llm-chat 的 prompt（system 指示、日期表、工具結果）長什麼樣。
   補上之後（帶著 traceId）就能從 trace 一路點到那次的 prompt，
   也補回原生 `.bat` 的 `--log-prompts-dir` 拿掉後少掉的 prompt 紀錄。
2. **收集器備援練習**：起兩台 Alloy 抓同一批 target（HA pair），各帶 `__replica__` 標籤，
   開 Mimir 的 HA tracker，親眼看「兩台送一樣的資料、Mimir 只留一份、關掉一台自動切換」。
   這個練習只有 Mimir 做得出來（Prometheus 收到兩份就存兩份）。

## 檔案結構

```
ops/
├── compose.yaml                              # 五個服務的定義
├── alloy/config.alloy                        # 三條管線：抓誰的指標、收哪些容器的 log、trace 轉給誰
├── mimir/mimir.yaml                          # 指標：單機最小設定（關掉多租戶、複製、物件儲存）
├── loki/loki-config.yaml                     # 日誌：單機最小設定
├── tempo/tempo.yaml                          # 追蹤：單機最小設定
└── grafana/provisioning/
    ├── datasources/datasources.yaml          # 自動接好 Mimir + Loki + Tempo，以及三者之間的跳轉
    └── dashboards/                           # talkcal-app、llama-cpp（指標）、talkcal-traces（trace）、talkcal-k6（壓測）、talkcal-cost（成本）
```
