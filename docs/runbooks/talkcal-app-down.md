# talkcal app 掛了或抓不到指標（`talkcal-app-down`）

| | |
|---|---|
| severity | **critical** —— 現在就處理 |
| 規則 | [`rules.yaml`](../../ops/grafana/provisioning/alerting/rules.yaml) 第 1 條，總表見 [alerts.md](../alerts.md) |
| 條件 | `max(up{job="talkcal-app"}) < 1`，持續 1 分鐘；**查不到資料也算出事** |

## 代表什麼

Alloy 每 15 秒去抓 app 的 `/actuator/prometheus`，連續 1 分鐘以上抓不到（`up` 是 0），或 `up` 這條整個不見了。
兩種很不一樣，第一步先分清楚：

| 看到的 | 意思 |
|--------|------|
| `up{job="talkcal-app"}` = 0 | Alloy 還活著、抓不到 app → **app 那頭有事** |
| `up{job="talkcal-app"}` 查不到 | Alloy 沒在送指標 → **可能是監控自己掛了**，app 不一定有事（同時應該有 `meta-alloy-down`） |

## 對使用者的影響

app 真的掛了的話**整個網站不能用**：前端（nginx）還在，但所有 `/api` 都是 502，登入不了。
如果只是監控抓不到（Alloy 掛了），使用者沒有影響，但這段時間的指標會缺。

## 怎麼確認

1. Grafana → Explore → Mimir：

   ```promql
   up{job="talkcal-app"}
   up                                   # 其他 job（llama-cpp、postgres、alloy）還有沒有值 → 有的話 Alloy 是活的
   ```

2. 直接打 app（不經過監控）：

   ```bash
   curl -s -w ' %{http_code}\n' http://127.0.0.1:18090/actuator/health    # 本機
   dc exec app curl -s localhost:8090/actuator/health                    # 雲端（dc 見 README）
   ```

   `{"status":"UP"}` 200 → app 好好的，問題在監控那頭（看原因 4）。

3. 容器狀態：

   ```bash
   docker compose ps app
   docker inspect talkcal-app --format '{{.State.Status}} OOMKilled={{.State.OOMKilled}} ExitCode={{.State.ExitCode}} RestartCount={{.RestartCount}}'
   ```

## 可能的原因（由常見到少見）

### 1. 正在部署，新版還沒起來

deploy.sh 剛跑過、`docker compose ps` 看到 app 是 `starting`。Spring 起來約 20～30 秒，超過 1 分鐘才會響。
等它 healthy；如果一直起不來，看下一個原因。

### 2. app 啟動失敗（設定、migration、連不上資料庫）

`docker compose ps` 是 `Restarting` 或 `Exited`，`RestartCount` 一直加。

```bash
docker compose logs --since 10m app | grep -iE "error|exception|caused by" | head -30
```

- `Flyway` 的錯誤 → 新版的 migration 有問題：**退回上一版**（雲端 `sudo GIT_REF=<上一個 commit> bash /opt/talkcal/repo/infra/envs/1-ec2/deploy.sh`），不要在線上手改 schema
- `Connection refused` 到 postgres → 先處理資料庫（[talkcal-db-down](talkcal-db-down.md)）
- 缺環境變數（`Could not resolve placeholder`）→ `.env` 少了值，雲端看 SSM 的 .env、`terraform apply` 再重跑 deploy.sh

### 3. 記憶體不足被砍（OOMKilled）

`OOMKilled=true`、ExitCode 137。雲端 app 有 `mem_limit`（`compose.ec2.yaml`）。

```bash
docker compose up -d app          # 先拉起來
docker stats --no-stream          # 看是不是整台機器都不夠
```

拉起來之後看 talkcal-app dashboard 的 JVM heap，是一直往上長（洩漏）還是尖峰。長期的解法要寫 issue。

### 4. app 好好的，是監控抓不到

步驟 2 的 health 是 200。

- `up` 整條查不到 → Alloy 停了：照 [meta-alloy-down](meta-alloy-down.md)
- `up` 是 0 但 app 是好的 → Alloy 連不到 app：Alloy 不在 deploy/ 的網路上（例如 deploy/ 那組重建過、網路換了），重起 Alloy：

  ```bash
  cd ops && docker compose up -d --force-recreate alloy
  ```

  開 Alloy 的除錯頁 <http://127.0.0.1:12345>，`prometheus.scrape.talkcal_app` 的 target 應該是 up。

### 5. 整台機器掛了（雲端）

SSM 連不進去、網址打不開。AWS Console 看 instance 狀態和 status check。機器自己停了就開機
（`aws ec2 start-instances …`，見 `infra/envs/1-ec2/README.md`），容器會自己起來。

## 怎麼確認已經恢復

- `up{job="talkcal-app"}` 回到 1，1～2 分鐘內收到 `[RESOLVED]`
- `docker compose ps app` 是 healthy；開網址能登入、看得到行程
- 停掉的這段時間 SLO 的 budget 會被扣（app 掛掉時 SLI 是「沒資料」，**不會**扣，扣的是剛恢復時的錯誤）

## 什麼時候往上升級

- 15 分鐘內拉不起來 → 退回上一個能用的版本，先恢復服務，原因之後查
- 退回也起不來 → 多半是資料庫或資料的問題，照 [備份與還原](backup-restore.md)

## 事後要不要寫 postmortem

**要**，只要使用者真的連不上（原因 1 超過幾分鐘、2、3、5）。原因 4（只有監控）照 meta 那幾條的規則。

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
| 2026-10-19 | 驗證時 Docker Desktop 整個停了（不是演練造的） | 重開 Docker 後 1 分鐘 | Alloy 也一起停，信是「app 掛了」；照原因 4 分得出來 |
