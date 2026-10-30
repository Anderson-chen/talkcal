# Alloy 停了，指標不再進來（`meta-alloy-down`）

| | |
|---|---|
| severity | **critical** —— 現在就處理 |
| 規則 | `meta-monitoring.yaml` 第 1 條（PR #175，#40），總表見 [alerts.md](../alerts.md) |
| 條件 | `time() - max(timestamp(up{job="alloy"}))` > 60 秒（最後一個點多舊），持續 2 分鐘；查不到也算出事 |

## 代表什麼

三種訊號（指標、log、trace）都經過 Alloy。它停了（或送不進 Mimir），Mimir 裡就不再有新的點。
**其他所有指標類告警這時都看不到東西**：app 掛了那條會一起響（它的 `up` 也消失了），其他多半變成「沒資料 = 正常」。

## 對使用者的影響

**沒有直接影響**，服務照常。影響的是我們：這段時間出事不會有告警，指標和 trace 會缺一段（補不回來），log 會在 Alloy 回來後補讀。

## 怎麼確認

1. 同時是不是也收到「talkcal app 掛了或抓不到指標」？是的話先確認 app 本身好不好（`curl -s http://127.0.0.1:18090/actuator/health`）—— app 好的話就是監控的問題
2. Alloy 在不在：

   ```bash
   cd ops && docker compose ps alloy
   docker compose logs --since 15m alloy | tail -30
   ```

3. Alloy 活著的話，開除錯頁 <http://127.0.0.1:12345>（雲端用 SSM port forwarding 接 12345），看 `prometheus.remote_write.mimir` 有沒有在報錯

## 可能的原因（由常見到少見）

### 1. Alloy 容器停了

```bash
cd ops && docker compose up -d alloy        # 雲端：oc up -d alloy（oc 見 README）
```

雲端要用 deploy.sh 那串參數（`--env-file`、`TEXTFILE_DIR`）起，最簡單是 `oc up -d alloy`；整組重起就重跑 deploy.sh。

### 2. Alloy 活著但送不進 Mimir

除錯頁 remote_write 一直失敗、log 有 `connection refused` 到 `mimir:9009` → Mimir 有事，同時應該有 `meta-mimir-down`，照 [meta-mimir-down](meta-mimir-down.md)。

### 3. 設定檔錯了，Alloy 起不來

剛改過 `ops/alloy/config.alloy`、log 有 `Error: ... config.alloy:<行>`。先驗設定：

```bash
docker run --rm -v "$PWD/ops/alloy:/c" grafana/alloy:v1.11.0 fmt /c/config.alloy
```

改回上一版（`git checkout HEAD~1 -- ops/alloy/config.alloy`）、重起，再走 PR 修。

### 4. Alloy 被 OOM 砍了

`docker inspect talkcal-alloy --format '{{.State.OOMKilled}}'` 是 true。通常是 log 量暴增（某個容器狂印 log）。找出是誰：

```logql
sum by (service) (count_over_time({env="docker"}[5m]))
```

## 怎麼確認已經恢復

- `up{job="alloy"}` 又有新的點，2～3 分鐘內收到 `[RESOLVED]`；app 那條也一起恢復
- talkcal-app dashboard 的圖接上了（中間缺的那段補不回來，是正常的）

## 什麼時候往上升級

- 15 分鐘起不來 → 這段時間沒有任何告警，改成人工看：每 10 分鐘自己開網址、打一次助理，直到修好

## 事後要不要寫 postmortem

停超過 15 分鐘、或缺的那段剛好有事故要查：**要**。重啟幾秒就好的：不會響。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-19 | 驗證（#40） | 見 PR #175 | |
