# ops —— 觀測環境（Observability）

用 Docker 一次拉起 **Alloy + Mimir + Loki + Tempo + Grafana**，跟被觀測的服務分開。
被觀測的是 `deploy/` 那組容器（app、兩顆模型），以及本機開發時用 `./gradlew bootRun` 跑的 app。

## 整條流程

每種訊號都走「產生 → 收集 → 儲存 → 查詢/畫圖」四段。收集器只有一個，資料庫一種訊號一個：

```
              產生                          收集             儲存          查詢/畫圖
指標 metrics  Micrometer → /actuator/prometheus ◀─抓(15s)─┐
              llama-server --metrics → /metrics ◀─抓──────┤
                                                          ├ Alloy ─▶ Mimir ─┐
日誌 logs     SLF4J/Logback → stdout(ECS JSON) ◀─讀───────┤       ─▶ Loki  ─┼─▶ Grafana
                                                          │       ─▶ Tempo ─┘
追蹤 traces   Micrometer Tracing → OTLP ──────推──────────┘
```

| 訊號 | 一筆資料長什麼樣 | 回答的問題 | 收集方式 | 存在 | 查詢語言 |
|------|------------------|------------|----------|------|----------|
| 指標 | 標籤 + 一個數字 | 有沒有問題、多嚴重（p95 變慢了） | Alloy **拉** | Mimir | PromQL |
| 日誌 | 一行文字（JSON） | 發生了什麼事（那個請求問了什麼） | Alloy **讀** stdout | Loki | LogQL |
| 追蹤 | 一棵 span 樹 | 時間花在哪一段（檢索還是生成） | app **推**給 Alloy | Tempo | TraceQL |

## 五個角色

| 服務 | 做什麼 | 對外埠 | 看它 |
|------|--------|--------|------|
| **Alloy** | 唯一的收集器：抓指標、讀容器 log、接 app 推來的 trace，分送給下面三個 | 12345、4318 | <http://localhost:12345>（管線圖）|
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

Alloy 加入了 deploy/ 的網路（`eat-deploy_default`）：用服務名稱抓指標，app 也用 `alloy:4318` 送 trace。
代價是**要先起 deploy/ 再起 ops/**，網路不存在時 Alloy 起不來。

## 指標：Alloy 抓誰、送去哪

| job | 位址 | 是誰 |
|-----|------|------|
| `eat-app` | `app:8090/actuator/prometheus` | deploy/ 的 app 容器 |
| `llama-cpp` | `llm-chat:8080/metrics` | Qwen3-8B（deploy/ 的 llm-chat） |
| `llama-embedding` | `llm-embedding:8081/metrics` | bge-m3（deploy/ 的 llm-embedding） |

每 15 秒抓一次，用 `remote_write` 推進 Mimir。本機 bootRun 的 app 不在 deploy/ 的網路裡，不會被抓。

抓不抓得到，看 <http://localhost:12345> 的 `prometheus.scrape.*` 元件，或在 Grafana 查 `up`（1 = 抓得到）。
想不透過 Grafana 直接查：

```bash
curl 'localhost:9009/prometheus/api/v1/query?query=up'
```

app 打給兩台模型的呼叫，原本有一組 `http_client_requests_seconds`（RestClient 自動產生）。
改用 Spring AI 之後沒有了：Spring AI 的 OpenAI 模組底層是官方 openai-java SDK（OkHttp），不走 RestClient。
取而代之的是 Spring AI 自己的觀測（`gen_ai.*` 系列，含 token 用量），見下面「追蹤」一節。

## 日誌：Loki 收什麼

Alloy 透過 Docker 的 API（`docker.sock`）自動發現 `deploy/` 那組容器（compose 專案 `eat-deploy`），
讀它們的 stdout 送進 Loki。新容器起來不必改設定；其他專案的容器、ops 自己的容器都不收。
管線怎麼接、為什麼這樣選標籤，見 `alloy/config.alloy` 的註解。

每筆 log 帶的標籤：

| 標籤 | 例子 | 用途 |
|------|------|------|
| `service` | `app`、`llm-chat`、`llm-embedding` | compose 服務名，最穩，查詢主要靠它 |
| `container` | `eat-app` | 容器名 |
| `env` | `docker` | 環境。之後別的環境的 log 也送進來時用它分開 |

trace id **不是**標籤（每個請求都不同，放進標籤會讓索引爆掉），它在 log 內容的 `traceId` 欄位裡。

本機開發的 bootRun 是跑在 Windows 上的程序、不在 Docker 裡，它的 log **不會**進 Loki，照常看終端機。

### 在 Grafana 查

左側 **Explore** → 資料源選 **Loki** → 切到 **Code** 模式貼查詢：

```logql
# 三個服務一起看，照時間排——一次提問會先看到 llm-embedding（檢索）、再看到 llm-chat（生成）
{env="docker"}

# 只看生成模型每次請求的耗時
{service="llm-chat"} |= "total time"

# app 的 log 是 JSON（docker profile 開的），| json 把欄位拆開，就能照等級篩
{service="app"} | json | log_level=~"WARN|ERROR"

# app 的 access log：每個請求一行，帶方法、路徑、請求與回應的 body、狀態碼、耗時、traceId（AccessLogFilter 寫的）
{service="app"} | json | log_logger="access"

# 只看失敗的提問，連同當時問了什麼、回了什麼
{service="app"} | json | log_logger="access" | http_response_status_code >= 400

# 超過 5 秒的慢請求（event_duration 是奈秒）
{service="app"} | json | log_logger="access" | event_duration > 5000000000

# 每 5 分鐘有幾筆 WARN/ERROR——從 log 算出數字，可以畫成圖
sum by (service) (count_over_time({env="docker"} | json | log_level=~"WARN|ERROR" [5m]))
```

## 追蹤：一個請求的時間花在哪

app 對每個請求開一個 trace（`management.tracing.sampling.probability=1.0`，學習環境全記），
用 OTLP 推給 Alloy 的 4318，Alloy 再轉給 Tempo。

> **改用 Spring AI 之後，下面這棵樹還沒重新量過。** 以前往外的兩個 span 是 RestClient 產生的 HTTP span
> （`http post /v1/embeddings`、`http post /v1/chat/completions`）；現在跟模型講話的是 Spring AI，
> 底層是官方 openai-java SDK（OkHttp），**不會**產生 HTTP span，請求也不再帶 `traceparent` 標頭。
> 換成 Spring AI 自己的觀測：ChatClient、advisor（記憶、RAG）、ChatModel、EmbeddingModel、VectorStore 各自一個 span，
> 模型那幾個帶 `gen_ai.*` 屬性（模型名稱、token 用量）。部署後打一題，到 Tempo 看實際的 span 名稱，再回來補這張圖。
> `eat — Traces (Tempo)` 儀表板裡用 `kind=client` 篩往外呼叫的那幾張圖，到時也要跟著改。

以前（RestClient 時代）一次 `/api/chat` 長這樣：

```
eat: http post /api/chat                   ← 請求進來（Spring 自動加的 ServerHttpObservationFilter 量）
├── http post /v1/embeddings       200     ← 檢索：問題轉向量（第一次提問會多好幾次：先替知識庫建索引）
└── http post /v1/chat/completions 200     ← 生成：qwen3 回答
```

| span | 誰量的 | 為什麼是它 |
|------|--------|------------|
| 根 span（進來的請求） | Spring Boot 自動註冊的 filter | 進來的 HTTP 由 Spring MVC 處理，它管得到，不必寫任何設定 |
| 跟模型的往返 | Spring AI（ChatClient、ChatModel、EmbeddingModel 內建的 Micrometer Observation） | 不必寫觀測程式碼；比 HTTP span 多了 token 用量，少了 HTTP 狀態碼 |

曾經用 AOP 在 outbound port 外面多包一層 span（名稱是 `retrievePassages`、`generateReply`），
後來拿掉了。Spring AI 自己的 span 本來就是照「檢索」「生成」這種業務動作切的，更用不著了（實際名稱同樣等部署後確認）。
llama.cpp 本身不產生 span，所以樹只到 app 呼叫出去的那一層。

想在 trace 上看到送給模型的完整 prompt（下一步第 1 項），Spring AI 有現成的開關：
`spring.ai.chat.client.observations.log-prompt=true`。prompt 裡可能有使用者的私人內容，所以預設關著，要開再想清楚。

在 Grafana 看 trace 有兩個地方：

- **儀表板 `eat — Traces (Tempo)`**：全部用 TraceQL 查 Tempo。上排是從 trace 算出來的數字（提問速率、
  檢索／生成各段的 p95、沒成功的 span 數），下排是請求清單（最近的、超過 5 秒的、沒成功的），點 Trace ID 就打開瀑布圖。
  查詢只挑 `/api/chat`，只看提問本身。（actuator 的抓取與健康檢查在 app 端就不產生 trace 了，見 `ObservationConfiguration`。）
- **Explore** → 資料源選 **Tempo** → **Search** 分頁，Service Name 選 `eat`：自己下條件找。

儀表板上排用的是 **TraceQL metrics**（`| rate()`、`| quantile_over_time()`）：Tempo 直接從存下來的 span 算出時間序列，
不必另外開 metrics-generator 把數字寫進 Mimir。代價是分位數用 2 的次方分桶估（0.5s、1s、2s…），
只適合看「慢在哪一段」的量級，精準的 p95 看 eat-app 儀表板（指標那條線）。

本機 bootRun 的 app 也會送 trace（走主機的 `127.0.0.1:4318`），只要 ops/ 有起來。
ops/ 沒起時 trace 送不出去，app 只會在 log 印 warning，請求照常處理。

## 三種訊號之間怎麼跳

| 從 | 到 | 怎麼點 | 靠什麼接起來 |
|----|----|--------|--------------|
| 指標（eat-app 儀表板的 p95 圖） | trace | 圖上的小菱形（exemplar）→ 點 trace_id | app 吐指標時在 histogram 的點上附 trace id，Mimir 存下來（`max_global_exemplars_per_user`） |
| log（Loki 的 access log） | trace | 展開一行 → **到 Tempo 看這個請求** | Loki 資料源的 derived field 從內容抓出 `traceId` |
| trace（Tempo） | log | span 旁的 **Logs for this span** | Tempo 資料源用 trace id 去 Loki 搜內容 |

## 下一步（一次一件）

1. **app 記錄送給模型的完整 prompt**：access log 已經記了使用者問了什麼（請求 body），
   但還看不到檢索到哪幾段、最後組出來送給 llm-chat 的 prompt 長什麼樣。
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
    └── dashboards/                           # eat-app、llama-cpp、llama-embedding（指標）、eat-traces（trace）
```
