# ops —— 觀測環境（Observability）

用 Docker 一次拉起 **Grafana + Prometheus + Loki + Alloy**，跟被觀測的服務分開。
被觀測的是 `deploy/` 那組容器（app、兩顆模型），以及本機開發時用 `./gradlew bootRun` 跑的 app。

## 四個角色

| 服務 | 做什麼 | 對外埠 | 看它 |
|------|--------|--------|------|
| **Grafana** | 畫圖前台，自己不存資料，去跟 Prometheus、Loki 要資料 | 3000 | <http://localhost:3000> |
| **Prometheus** | 存**指標(metrics)**：每隔 15 秒去抓各服務吐的數字 | 9090 | <http://localhost:9090> |
| **Loki** | 存**日誌(logs)**：集中放 log，在 Grafana 同一介面查 | 3100 | （沒有 UI，靠 Grafana 查）|
| **Alloy** | 收集器：讀容器的 stdout，加上標籤送進 Loki | 12345 | <http://localhost:12345>（管線除錯頁）|

Grafana 一啟動就自動接好 Prometheus、Loki 兩個資料源（見 `grafana/provisioning/`），
開網頁不必登入，直接是 Admin（純本機學習用，刻意關掉登入）。

## 怎麼跑

```bash
cd ops
docker compose up -d        # 背景拉起四個容器
docker compose ps           # 看狀態
docker compose logs -f      # 追 log（Ctrl+C 離開，不會關容器）
docker compose down         # 關掉（資料留著）
docker compose down -v      # 關掉並清空資料，整組重來
```

## 指標：Prometheus 抓誰

| job | 位址 | 是誰 |
|-----|------|------|
| `eat-app` | `host.docker.internal:8090` | **本機開發**的 app（bootRun） |
| `llama-cpp` | `host.docker.internal:8080` | Qwen3-8B（原生 .bat 或 deploy/ 的容器，埠一樣） |
| `llama-embedding` | `host.docker.internal:8081` | bge-m3（同上） |

容器裡的 app（18090）目前**沒有**被抓，見「下一步」。

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

# 每 5 分鐘有幾筆 WARN/ERROR——從 log 算出數字，可以畫成圖
sum by (service) (count_over_time({env="docker"} | json | log_level=~"WARN|ERROR" [5m]))
```

## 下一步（一次一件）

1. **抓容器 app 的指標**：在 `prometheus.yml` 加 `host.docker.internal:18090`。
   要先決定怎麼跟本機開發的 8090 分開——現在 `eat-app.json` 的查詢沒有過濾 job/instance，
   兩個一起抓的話數字會混在一起。
2. **app 記錄每次提問**：目前 app 每個請求不寫 log，Loki 裡只看得到模型那兩台的請求紀錄。
   補上之後（送出的 prompt、檢索到哪幾段）就能在 Loki 串起一個請求的完整經過，
   也補回原生 `.bat` 的 `--log-prompts-dir` 拿掉後少掉的 prompt 紀錄。

## 檔案結構

```
ops/
├── compose.yaml                              # 四個服務的定義
├── prometheus/prometheus.yml                 # 抓誰、多久抓一次
├── loki/loki-config.yaml                     # 單機最小設定
├── alloy/config.alloy                        # 收哪些容器的 log、帶什麼標籤
└── grafana/provisioning/
    ├── datasources/datasources.yaml          # 自動接好 Prometheus + Loki
    └── dashboards/                           # eat-app、llama-cpp、llama-embedding 三份儀表板
```
