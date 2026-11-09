# Mimir 查不到，指標類告警全部失效（`meta-mimir-down`）

| | |
|---|---|
| severity | **critical** —— 現在就處理 |
| 規則 | `meta-monitoring.yaml` 第 2 條（PR #175，#40），總表見 [alerts.md](../alerts.md) |
| 條件 | Grafana 查 Mimir 失敗（連不上、逾時），持續 2 分鐘 |

## 代表什麼

Grafana 每一條指標類告警都要查 Mimir。Mimir 查不到時，那些告警全部變成「查詢錯誤」，**不會正常觸發**。
Alloy 會先把指標留在自己的 WAL，Mimir 回來後補送，短時間停掉不太會缺資料。

## 對使用者的影響

**沒有直接影響**。影響的是：這段時間 app、資料庫、SLO 出事都不會寄信，dashboard 一片空白。

## 怎麼確認

```bash
cd ops && docker compose ps mimir
docker compose logs --since 15m mimir | grep -iE "level=(error|warn)" | tail -20
curl -s 'http://127.0.0.1:9009/ready'          # 本機；回 ready 才算好
```

## 可能的原因（由常見到少見）

### 1. Mimir 容器停了或在重啟（WAL 重放中）

```bash
cd ops && docker compose up -d mimir           # 雲端：oc up -d mimir
```

重啟後要重放 WAL，資料多時一兩分鐘才 ready，等 `/ready` 回 ready。

### 2. 磁碟滿

log 有 `no space left on device`。Mimir 的資料在 `mimir-data` volume。先清 Docker 的 build cache、舊映像檔（見 [talkcal-db-down](talkcal-db-down.md) 原因 2），**不要刪 mimir-data**（刪了歷史指標就沒了，SLO 的 28 天也會歸零）。

### 3. 記憶體不足被砍

`docker inspect talkcal-mimir --format '{{.State.OOMKilled}}'` 是 true。拉起來；長期看是不是標籤太多（high cardinality）：

```bash
curl -s 'http://127.0.0.1:9009/prometheus/api/v1/status/tsdb' | head -c 2000
```

### 4. 錄製規則或設定檔錯了

剛改過 `ops/mimir/mimir.yaml` 或 `ops/mimir/rules/`，log 有設定錯誤。改回上一版、重起，再走 PR 修。

## 怎麼確認已經恢復

- `/ready` 回 ready；Grafana 的 Explore 查得到 `up`
- 2～3 分鐘內收到 `[RESOLVED]`；其他告警恢復正常評估（Alerting 頁不再有 Error）
- 這段時間如果有其他事，回來後告警會照常響 —— 看一下 Alerting 頁有沒有新的 Firing

## 什麼時候往上升級

15 分鐘起不來 → 跟 Alloy 停了一樣，改成人工看服務。資料檔壞了起不來 → 最後手段是清掉 `mimir-data` 重來（歷史指標會沒了），先停下來想清楚。

## 事後要不要寫 postmortem

停超過 15 分鐘：**要**。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-19 | 驗證（#40） | 見 PR #175 | |
