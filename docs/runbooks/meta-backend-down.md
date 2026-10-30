# Loki 或 Tempo 抓不到（`meta-backend-down`）

| | |
|---|---|
| severity | warning —— 上班時間處理 |
| 規則 | `meta-monitoring.yaml` 第 3 條（PR #175，#40），總表見 [alerts.md](../alerts.md) |
| 條件 | `min by (job) (up{job=~"loki\|tempo"}) < 1`，持續 2 分鐘；信上的 `job` 說是哪一個 |

## 代表什麼

Alloy 還活著，但抓不到 Loki 或 Tempo 自己的指標 —— 多半是它停了。

| 停的是 | 這段時間 |
|--------|---------|
| Loki | log 先留在 Docker 自己的 log 檔，Loki 回來後 Alloy 補送；只是**現在查不到 log** |
| Tempo | trace 會**掉**（Alloy 只在記憶體排隊重試，排不下就丟） |

## 對使用者的影響

沒有。指標和告警都還在，少的是查事故時要用的 log 或 trace。

## 怎麼確認

```bash
cd ops && docker compose ps loki tempo
docker compose logs --since 15m loki | tail -20       # 換成 tempo
```

## 可能的原因（由常見到少見）

### 1. 容器停了

```bash
cd ops && docker compose up -d loki tempo             # 雲端：oc up -d loki tempo
```

### 2. 磁碟滿

log 有 `no space left on device`。清 Docker 的 build cache、舊映像檔；不要刪 `loki-data`、`tempo-data`。

### 3. 設定檔錯了

剛改過 `ops/loki/` 或 `ops/tempo/`。改回上一版、重起，再走 PR 修。

## 怎麼確認已經恢復

- `up{job="loki"}`、`up{job="tempo"}` 回到 1，收到 `[RESOLVED]`
- Explore 查得到最近的 log／trace

## 什麼時候往上升級

同時有使用者回報問題、需要 log 或 trace 查 → 先修好這個再查，不然看不到東西。

## 事後要不要寫 postmortem

不必，在下面紀錄一行。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-19 | 驗證（#40） | 見 PR #175 | |
