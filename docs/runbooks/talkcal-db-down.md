# talkcal 資料庫連不上（`talkcal-db-down`）

| | |
|---|---|
| severity | **critical** —— 現在就處理 |
| 規則 | [`respond.yaml`](../../ops/grafana/provisioning/alerting/respond.yaml) 第 1 條，總表見 [alerts.md](../alerts.md) |
| 條件 | `pg_up{job="postgres"}` 為 0（exporter 連不上 PostgreSQL），或 exporter 本身抓不到（`up{job="postgres"}` 為 0），持續 1 分鐘 |

## 代表什麼

postgres_exporter 每 15 秒自己去連一次資料庫，連續 1 分鐘以上連不上。不靠使用者的流量，所以半夜沒人用也會響。

也可能是 **exporter 自己壞了、資料庫其實好好的**：條件裡 exporter 抓不到也算 0（看不到資料庫就不能當沒事）。第一步先分清楚是哪一個。

## 對使用者的影響

資料庫真的掛了的話，**所有功能都不能用**：行程的增刪改查、登入（session 存在資料庫）、AI 助理（對話記憶存在資料庫）。
使用者看到的是 502／500，前端顯示「伺服器出了問題」（#43）。

如果是 exporter 壞了，使用者**沒有影響**，只是看不到資料庫那一頭的指標。

**同一件事會連帶收到好幾封**（2026-10-20 演練實測，有流量時）：「有請求等不到資料庫連線」（比這封早約 1 分鐘，請求等滿 30 秒逾時）、
「SLO 快燒」、幾分鐘後「SLO 慢燒」。它們都是這個原因的結果，照這份處理就好，不用分頭查。

## 怎麼確認

1. **是資料庫還是 exporter？** Grafana → Explore → Mimir：

   ```promql
   pg_up{job="postgres"}          # 有值且是 0 → exporter 活著、資料庫連不上
   up{job="postgres"}             # 0 → exporter 本身抓不到（看下面原因 4）
   ```

2. **使用者那頭有沒有出事**：talkcal-app dashboard 的 5xx、或

   ```promql
   sum(rate(http_server_requests_seconds_count{job="talkcal-app", status=~"5.."}[5m]))
   ```

   沒流量時這裡是空的，不代表沒事 —— 直接問 app 的 health（它會檢查資料庫）：

   ```bash
   curl -s -w ' %{http_code}\n' http://127.0.0.1:18090/actuator/health   # 本機
   dc exec app curl -s localhost:8090/actuator/health                   # 雲端（dc 見 README）
   ```

   `{"status":"DOWN"}` 503 → app 連不上資料庫；`UP` 200 → 資料庫其實沒事，看原因 4。

3. **容器狀態**：

   ```bash
   docker compose ps postgres postgres-exporter app
   ```

## 可能的原因（由常見到少見）

### 1. postgres 容器停了或在重啟

`docker compose ps` 看到 postgres 是 `Exited`、`Restarting`，或根本不在列表裡。

```bash
docker compose logs --since 30m postgres | tail -50      # 先看為什麼停（OOM、磁碟滿、設定錯）
docker compose up -d --wait postgres                     # 拉起來，--wait 等到 healthy 才回
```

雲端用 `dc up -d --wait postgres`。**不要用 `down -v`**：`-v` 會把資料庫的 volume 一起刪掉。

起來之後 app 的連線池會自己重連（HikariCP），不必重啟 app。確認 app 也恢復：`docker compose ps app` 是 healthy。

### 2. 磁碟滿了

postgres 的 log 有 `No space left on device`、或 `could not write to file`。

```bash
docker system df                     # Docker 自己用了多少（映像檔、volume、build cache）
df -h /                              # 雲端：整顆磁碟
docker builder prune -f              # 最安全的清法：build cache（部署時 --build 會累積）
docker image prune -f                # 沒在用的舊映像檔
```

清完再照原因 1 把 postgres 拉起來。**不要刪 volume**（`docker volume rm`、`down -v`），那就是資料本身。
雲端長期的解法是加大 `root_volume_size`（`infra/envs/1-ec2/variables.tf`）再 apply。

### 3. 記憶體不足，postgres 被 OOM killer 砍掉

`docker inspect talkcal-postgres --format '{{.State.OOMKilled}}'` 是 `true`，或雲端 `sudo dmesg -T | grep -i oom` 看得到。

```bash
docker stats --no-stream             # 誰在吃記憶體（app 有 mem_limit，llm-chat 最大）
docker compose up -d --wait postgres
```

雲端的機器記憶體不大（m7i-flex.large 8GB），如果是 app 長期吃太多，看 talkcal-app dashboard 的 JVM heap。

### 4. exporter 自己壞了（資料庫其實沒事）

`up{job="postgres"}` 是 0，但 app 的 API 都正常。

```bash
docker compose logs --since 15m postgres-exporter | tail -20
docker compose up -d postgres-exporter
```

常見是 `DB_PASSWORD` 換了（`terraform apply -replace=random_password.db`）但 exporter 沒重建：`docker compose up -d --force-recreate postgres-exporter`。

### 5. 密碼對不上（換了密碼、資料庫沒同步）

postgres log 有 `password authentication failed for user "talkcal"`，app 和 exporter 一起連不上。
雲端重跑部署，它會用 `ALTER ROLE` 把資料庫裡的密碼同步成 `.env` 那份（`deploy.sh` 第 4 步）：

```bash
sudo bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh
```

### 6. 資料檔壞了，postgres 起不來

postgres log 一直出 `PANIC`、`invalid page`、`could not read block`，重啟也起不來。
這時要**還原**：照 [備份與還原](backup-restore.md) 的「情境 B」用最近一份備份換上。動手之前先往上升級（見下面）。

## 怎麼確認已經恢復

- `pg_up{job="postgres"}` 回到 1，告警變 Normal，信箱收到 `[RESOLVED] talkcal 資料庫連不上`（Grafana 每分鐘評估一次，最多等 1～2 分鐘）
- `docker compose ps` 的 postgres、app 都是 healthy
- 打一次需要資料庫的 API（開網頁列一下行程）是 200
- 停掉的這段時間 SLO 會燒掉一些 budget：看 **talkcal — SLO** 儀表板，`calendar-availability` 剩多少

## 什麼時候往上升級

- 原因 1～5 做完 **15 分鐘**還連不上 → 先放維護說明（前端已經會顯示「伺服器出了問題」），不要繼續在線上猜
- 要還原（原因 6）→ 先停下來，照 [備份與還原](backup-restore.md) 一步一步做，還原前一定先 `--verify-only` 演練
- 雲端整台機器連不上（SSM 也連不進去）→ AWS Console 看 instance 的 status check，必要時 stop／start（資料在 EBS，不會掉）

## 事後要不要寫 postmortem

**要**，只要使用者真的連不上（原因 1、2、3、5、6）。原因 4（只有 exporter 壞）不必，在下面紀錄一行。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-20 | 演練：`docker stop postgres`（原因 1） | 照 runbook 39 秒；故障到恢復信約 12 分鐘 | [演練紀錄](../drills/2026-10-20-runbook-drills.md)；補了「同一件事會連帶收到好幾封」 |
