# ③ 跑起來之後（Run）

[← 回上線準備清單](../production-readiness.md)

<a id="three-signals"></a>
### 指標、log、trace 三種訊號 ✅

[ops/](../../ops/README.md)：Alloy → Mimir / Loki / Tempo → Grafana，有 3 個 dashboard（app、llama.cpp、traces）。

<a id="structured-log"></a>
### 結構化 log 帶 trace id ✅

容器裡的 log 用 ECS 格式；證據是 [AccessLogTraceIdTest](../../backend/src/test/java/io/github/andersonchen/talkcal/app/observability/AccessLogTraceIdTest.java)。

<a id="request-correlation"></a>
### 一個請求從頭串到尾 ❌（部分做到）

目標是：拿到一個 trace id（或在圖上點一個慢點），一個畫面就看到這個請求經過哪些服務、每一段花多久、印了哪些 log、當時各服務的狀態。

**已經有的**：三種訊號之間可以互相跳轉，設定在 [datasources.yaml](../../ops/grafana/provisioning/datasources/datasources.yaml)。

| 從 | 到 | 怎麼接的 |
|---|---|---|
| Mimir 圖上的慢點 | Tempo 的 trace | exemplar 帶 `trace_id` |
| Loki 的一行 log | Tempo 的 trace | derived field 從 ECS JSON 抓 `traceId` |
| Tempo 的 trace | Loki 的 log | `tracesToLogsV2`，用 trace id 搜 log |

**缺的**：

| 缺口 | 後果 | 怎麼補 |
|---|---|---|
| 資料庫查詢不在 trace 裡 | trace 上看不到 SQL 花多久，慢的時候分不出是模型慢還是資料庫慢 | 加 JDBC 的 observation（例如 `datasource-micrometer-spring-boot`），每條 SQL 變成一個 span |
| trace 從 app 才開始 | 瀏覽器和前端 nginx 那一段看不到，使用者感受到的時間和 trace 上的時間對不起來 | nginx 轉傳 `traceparent`；前端之後再考慮 Grafana Faro |
| 沒有服務關係圖 | 看不出請求走過哪幾個服務、哪一條邊在出錯 | 開 Tempo 的 metrics-generator（service graph、span metrics），Grafana 就能畫 service map |
| trace 跳不到指標 | 看到慢的 trace，不能一鍵看「那時候 app、模型、連線池是什麼狀態」 | Tempo 資料源設定 `tracesToMetrics`，連到 Mimir 的延遲、錯誤率和[連線池](#db-pool)查詢 |
| 沒有「單一請求」的 dashboard | 現在要在三個 dashboard、Explore 之間來回跳 | 做一個以 `traceId` 為變數的 dashboard，見下面的設計 |

**「單一請求」dashboard 的設計**：

```
┌──────────────────────── 變數：traceId（從告警、log、前端錯誤訊息貼進來）────────────────────────┐
│ ① service map：browser → nginx → app → postgres / llama-server，邊上標延遲和錯誤率          │
├──────────────────────────────────────────────┬─────────────────────────────────────────────┤
│ ② 這個請求的 trace（瀑布圖）                    │ ③ 這個請求的 log（Loki，用 traceId 過濾）      │
│   HTTP → 助理 → 叫模型 ×N → SQL                │   依時間排序，錯誤標紅                         │
├──────────────────────────────────────────────┴─────────────────────────────────────────────┤
│ ④ 那段時間的狀態（時間範圍自動對齊 trace 前後各 5 分鐘）                                         │
│   app：req/s、p95、5xx　｜　連線池：active / idle / pending　｜　llama：處理中 / 排隊中、tokens/s  │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

由上往下讀：①先看是哪一段出事，②再看那一段花了多久，③看它說了什麼，④最後看是它自己的問題，還是當時整個系統都在塞。

<a id="db-pool"></a>
### 資料庫連線池 ❌

**現況**：Spring Boot 預設用 HikariCP，連線池大小沒設，所以是預設的 10 條。Micrometer 會自動把 HikariCP 的指標放上 `/actuator/prometheus`，Alloy 應該已經抓進 Mimir（**待確認**），但 [talkcal-app dashboard](../../ops/grafana/provisioning/dashboards/talkcal-app.json) 沒有任何連線池的面板。資料庫被塞住的時候，看到的只會是「API 變慢」，看不出原因是在排隊等連線。

**要看的東西**：

| 想知道 | 指標（Micrometer 名稱，`pool` 標籤區分連線池） | 怎麼判讀 |
|---|---|---|
| 現在用了幾條 | `hikaricp_connections_active` | 長期貼著上限代表池太小，或有查詢沒放手 |
| 閒著幾條 | `hikaricp_connections_idle` | active + idle = 目前開著的連線數 |
| 上限、下限 | `hikaricp_connections_max`、`hikaricp_connections_min` | 畫成水平線，跟 active 疊在同一張圖 |
| 有幾個在排隊等連線 | `hikaricp_connections_pending` | 大於 0 就是池不夠用了，延遲會跟著上去 |
| 等連線等多久 | `hikaricp_connections_acquire_seconds` | 等太久會變成逾時 |
| 拿不到連線而失敗 | `hikaricp_connections_timeout_total` | 只要增加就該告警 |
| 一條連線被占用多久 | `hikaricp_connections_usage_seconds` | 變長代表查詢變慢，或交易開太久 |

資料庫那一端也要看：PostgreSQL 的 `max_connections`（預設 100）和 `pg_stat_activity` 裡各狀態的連線數，要接 `postgres_exporter`。app 的池開多大，都不能超過資料庫的上限扣掉保留的連線。

**做完的證據**：talkcal-app dashboard 裡有一排連線池的面板，k6 壓測時截圖 active 貼近 max、pending 開始上升的那一刻；同一張圖也放進[單一請求 dashboard](#request-correlation) 的 ④。

<a id="health"></a>
### 健康檢查 ✅

compose 的 healthcheck 打 `/actuator/health`。目前只有一種檢查，還沒分成 liveness（程序卡死要重啟）和 readiness（暫時不收流量），搬上 k8s 時要分開。

<a id="actuator"></a>
### Actuator 暴露面 ❌（部分做到）

只開了 `health` 和 `prometheus`，但跟 API 用同一個埠，沒有分開只給內網用。

<a id="capacity"></a>
### 壓測與容量 ✅

[perf/](../../perf/README.md) 有 smoke、load、knee 三種壓測，用 Little's Law 驗算過。k6 的指標還沒接進 Grafana，壓測曲線不能跟 app、模型的指標疊在同一張圖上看。

<a id="slo"></a>
### SLO ❌

還沒定義怎樣才算「夠好」。dashboard 看得到數字，但沒有可以比較的標準。

<a id="cost"></a>
### 成本 ❌

沒有記錄 token 用量，也沒算過自架 GPU 和雲端 API 在多少用量時成本會交叉。

<a id="product-metrics"></a>
### 產品指標 ❌

知道系統健不健康，但不知道功能有沒有人用，例如助理提議的行程被採納了幾成。

<a id="retention"></a>
### 資料保存期限 ✅

[AssistantMemoryCleanup](../../backend/src/main/java/io/github/andersonchen/talkcal/app/job/AssistantMemoryCleanup.java) 每天清掉 30 天沒說話的對話。

<a id="sampling"></a>
### Trace 取樣率 ❌（部分做到）

目前是 100% 取樣，本機沒問題，上線流量大的時候要調整。
