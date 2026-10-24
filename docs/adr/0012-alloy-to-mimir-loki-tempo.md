# 0012. 觀測：只有 Alloy 一個收集器，分送 Mimir、Loki、Tempo

- 狀態：已採用
- 日期：2026-10-02
- 相關：[c429b59 觀測環境改成 Grafana 全家](https://github.com/Anderson-chen/talkcal/commit/c429b59)、epic [#30](https://github.com/Anderson-chen/talkcal/issues/30)、上 EKS 時怎麼放見 [0003](0003-self-hosted-observability-on-s3.md)

## 背景

一開始是 Prometheus（抓 + 存指標）+ Loki（log），log 由 Alloy 讀容器的 stdout。接著要加 trace，
而且想在 Grafana 裡三種訊號互相點得過去：指標的 exemplar → trace、log 的 traceId → trace、trace → log。

## 選項

收集器：

| 選項 | 代價 |
|---|---|
| 每種訊號各一個（Prometheus 抓指標、Promtail 收 log、app 直接送 Tempo） | 三套標籤各自設，換儲存要動 app；Promtail 官方已停止開發 |
| 只有 Alloy | Alloy 是單點 |

存指標：

| 選項 | 好處 | 代價 |
|---|---|---|
| Prometheus | 輕、單機夠用 | 保存期限受硬碟限制、一台掛掉資料就沒了、沒有多租戶和 HA 去重 |
| Mimir | 物件儲存、寫多份、多租戶、HA tracker；查詢仍是 PromQL，儀表板一個字不用改 | 比較重；單機要把一半的分散式功能關掉 |

trace 要另外一個庫（Tempo）：trace 是一棵 span 樹，Mimir 只放得下「標籤 + 數字」、Loki 是一行一行文字，都裝不下。

## 決定

**Alloy 是唯一的收集器：抓指標 remote_write 到 Mimir、讀 stdout 送 Loki、收 OTLP trace 送 Tempo。三種訊號用同一套標籤（`service`、`env`）。**

選 Mimir 不是現在的量需要它，而是為了學正式環境的形狀（[ops/README.md](../../ops/README.md#為什麼存指標用-mimir-而不是-prometheus) 的對照表）。
app 只認得 OTLP、只知道 Alloy 在哪；之後換儲存，app 不必重新部署。

## 代價

- Alloy 是單點，但兩種訊號斷掉的後果不同：log 只會延遲（Docker 照樣寫檔，Alloy 回來接著讀）；指標會缺一段（拉取只拿得到當下的值）。目前只靠 `restart: unless-stopped`。
- 要先起 `deploy/` 再起 `ops/`：Alloy 要加入 `deploy/` 建的網路。
- Mimir 單機設定大半是在關掉分散式功能。

## 什麼時候重想

- 上 EKS：繼續自架、區塊改存 S3（已決定，[0003](0003-self-hosted-observability-on-s3.md)）。
- 量大到單一 Alloy 撐不住，或要 HA → 多台 Alloy + Mimir 的 HA tracker 去重。

## 證據

- [ops/README.md 的選型理由](../../ops/README.md#選型理由)
- [ops/alloy/config.alloy](../../ops/alloy/config.alloy)：管線怎麼接、為什麼這樣選標籤
- [ops/compose.yaml](../../ops/compose.yaml)、[ops/mimir](../../ops/mimir)、[ops/tempo](../../ops/tempo)
