# talkcal 資料庫備份太久沒成功（`talkcal-backup-stale`）

| | |
|---|---|
| severity | warning —— 上班時間處理（但今天內要處理完） |
| 規則 | [`respond.yaml`](../../ops/grafana/provisioning/alerting/respond.yaml) 第 4 條，總表見 [alerts.md](../alerts.md) |
| 條件 | 距離上次成功備份超過 2 小時 15 分，**而且**開機也超過 2 小時 15 分 |
| 只有雲端 | 本機的備份是手動的，沒有這個指標，這條不會響 |

## 代表什麼

雲端每小時整點備份一次（`talkcal-backup.timer` → `backup-to-s3.sh`，#45）。成功傳上 S3 時才會寫
`/opt/talkcal/metrics/backup.prom`（最後成功的時間），Alloy 讀進來。
這條響代表**連續兩個整點的備份都沒有成功**。

剛開機的 2 小時 15 分內不會響：機器平常關機，開機時上一份可能是一週前關機前那份，那不是失敗。

## 對使用者的影響

**現在沒有**。資料都還在，服務照常。
影響的是「萬一」：這時候資料庫壞掉或被誤刪，能救回來的只到上一次成功的備份，中間寫進去的行程和對話會掉（RPO 從 1 小時變成告警信上寫的那麼久）。

## 怎麼確認

1. 告警信的 summary 寫了多久沒成功。Grafana → Explore → Mimir：

   ```promql
   time() - talkcal_backup_last_success_timestamp_seconds{job="talkcal-backup"}   # 秒
   ```

2. 連上機器（見 [README](README.md#雲端ec2)），看排程和最近幾次的輸出：

   ```bash
   systemctl list-timers talkcal-backup.timer          # 下次什麼時候跑、上次什麼時候跑
   journalctl -u talkcal-backup.service -n 50 --no-pager
   cat /opt/talkcal/backups/last-success               # 最後成功（UTC），跟告警應該對得上
   ls -lt /opt/talkcal/backups/ | head                 # 機器上最新的幾份
   ```

3. S3 上最新的是哪一份：

   ```bash
   aws s3 ls "s3://$(. /opt/talkcal/deploy.env; echo ${BACKUPS_BUCKET:-talkcal-backups-$(aws sts get-caller-identity --query Account --output text)})/postgres/" | tail -5
   ```

## 可能的原因（由常見到少見）

### 1. 傳 S3 失敗（權限、網路、bucket 不存在）

journalctl 裡 `pg_dump` 那段成功（`[pg-backup] 完成：…`），後面 `aws s3 cp` 報錯：

- `AccessDenied`：EC2 角色沒有寫這個 bucket 的權限 —— 常見於 #45 剛合併、還沒 `terraform apply` 就跑了 deploy.sh
- `NoSuchBucket`：bucket 還沒建
- 連線逾時：機器連不到 S3（網路、DNS）

處理（在本機 `infra/envs/1-ec2/`）：

```bash
terraform plan        # 看 bucket、iam 的權限是不是少了
terraform apply
```

然後在機器上手動補一份，確認會成功：

```bash
sudo bash /opt/talkcal/repo/infra/envs/1-ec2/backup-to-s3.sh manual
```

### 2. 排程沒在跑

`systemctl list-timers` 看不到 `talkcal-backup.timer`，或 `NEXT` 是空的。通常是 deploy.sh 第 6 步沒跑到（中途失敗）。

```bash
sudo systemctl enable --now talkcal-backup.timer
sudo bash /opt/talkcal/repo/infra/envs/1-ec2/backup-to-s3.sh manual   # 先補一份
```

### 3. pg_dump 失敗（資料庫沒在跑、磁碟滿）

journalctl 裡是 `[pg-backup]` 那段就失敗了：

- `Error response from daemon: container ... is not running`：資料庫停了 → 同時應該也有 `talkcal 資料庫連不上`，先照 [talkcal-db-down](talkcal-db-down.md) 處理
- `No space left on device`：磁碟滿 → 清 Docker 的 build cache（`docker builder prune -f`），再補一份

### 4. 備份成功了，但告警的指標沒更新

`last-success` 是新的，但告警還在響。看 `/opt/talkcal/metrics/backup.prom` 有沒有、時間對不對，以及 Alloy 有沒有掛到這個資料夾：

```bash
cat /opt/talkcal/metrics/backup.prom
sudo docker exec talkcal-alloy cat /var/lib/alloy/textfile/backup.prom     # 容器裡看得到同一份才對
```

容器裡看不到 → ops/ 那組不是用 deploy.sh 起的（少了 `TEXTFILE_DIR`），重跑 deploy.sh 或照它第 5 步那行重起 ops/。

## 怎麼確認已經恢復

- 手動補的那份成功：`[backup-to-s3] manual：完成 → s3://…`
- `cat /opt/talkcal/backups/last-success` 是剛剛的時間
- 一分鐘內 Alloy 讀到新的 `.prom`，下一次評估告警解除、收到 `[RESOLVED]`
- 等下一個整點，`journalctl -u talkcal-backup.service -n 5` 是成功的（確認排程本身也好了）

## 什麼時候往上升級

- 補不出一份成功的備份、又有人在用 → 先在本機留一份：`sudo bash /opt/talkcal/repo/deploy/backup/pg-backup.sh`，`scp`／SSM 下載回本機，再慢慢查 S3
- 超過一天都沒有成功的備份 → 這段期間不要部署（deploy.sh 部署前會先備份，失敗就停，是刻意的）

## 事後要不要寫 postmortem

- 原因 1、2（設定或部署順序造成）：**要**，寫在 PR 或 postmortem 裡，重點是「部署流程怎麼讓它不再發生」
- 一次性的網路問題、下一個整點自己好了：這條根本不會響（連兩次才響）

## 紀錄

| 日期 | 原因 | 花多久恢復 | 備註 |
|------|------|-----------|------|
