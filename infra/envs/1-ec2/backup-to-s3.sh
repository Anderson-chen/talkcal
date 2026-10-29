#!/usr/bin/env bash
# 在 EC2 上跑：備份資料庫、傳到 S3（#45）。不必手動跑，三個時機會自動叫它：
#   hourly     —— systemd timer，開機期間每小時一次
#   shutdown   —— 關機時（systemd 在停 Docker 之前），所以「關機前最後寫進去的」也有備份
#   pre-deploy —— deploy.sh 在換新版之前（新版的 Flyway migration 改壞資料時，回得去部署前那一刻）
# 這三個 unit 由 deploy.sh 第 6 步裝上。手動補一份：sudo bash /opt/talkcal/repo/infra/envs/1-ec2/backup-to-s3.sh manual
#
# 做法：pg-backup.sh（跟本機同一支）寫到機器上的 /opt/<project>/backups，再傳到 S3 的 postgres/ 底下。
# 機器上只留 2 天（快速還原用），S3 才是真正的備份（機器整台沒了也還在），保留期在 backups.tf。
set -euo pipefail

reason="${1:-manual}"

DEPLOY_ENV=/opt/talkcal/deploy.env
# shellcheck source=/dev/null
[ -f "$DEPLOY_ENV" ] && source "$DEPLOY_ENV"
: "${PROJECT:?}" "${AWS_REGION:?}"
# bucket 名稱照 backups.tf 的命名規則推出來，不寫進 deploy.env：那份由 user_data 寫，只在第一次開機跑，
# 已經在跑的機器拿不到；改 user_data 還會讓 Terraform 停機更新。get-caller-identity 不需要任何 IAM 權限
BACKUPS_BUCKET="$PROJECT-backups-$(aws sts get-caller-identity --region "$AWS_REGION" --query Account --output text)"

ROOT="/opt/$PROJECT"
# 容器名稱用 pg-backup.sh 的預設（talkcal-postgres）：deploy/compose.yaml 的 container_name 是寫死的，不跟著 PROJECT 變
export BACKUP_DIR="$ROOT/backups" KEEP_DAYS=2 KEEP_MIN=3

# 三個時機可能撞在一起（整點剛好在部署、或部署時關機）：同一時間只跑一份，後到的等前一份做完再做。
# 不鎖的話，同一秒開始的兩份會寫同一個檔名、互相刪掉對方的暫存檔
mkdir -p "$BACKUP_DIR"
exec 9> "$BACKUP_DIR/.lock"
flock 9

echo "[backup-to-s3] $reason：開始"
dump="$(bash "$ROOT/repo/deploy/backup/pg-backup.sh")"

# 先傳 dump、再傳雜湊檔：S3 上看得到 .sha256，就代表它的 dump 已經完整傳上去了
dest="s3://$BACKUPS_BUCKET/postgres/"
aws s3 cp "$dump" "$dest" --region "$AWS_REGION" --only-show-errors
aws s3 cp "$dump.sha256" "$dest" --region "$AWS_REGION" --only-show-errors

# 最後一次成功的時間，給人用 cat 看
date -u +%Y-%m-%dT%H:%M:%SZ > "$BACKUP_DIR/last-success"
# 同一件事給 Alloy 讀（textfile collector 的格式），備份太久沒成功就告警（#39）。
# 先寫暫存檔再 mv：Alloy 隨時可能來讀，mv 是原子的，不會讀到寫一半的檔
METRICS_DIR="$ROOT/metrics"
mkdir -p "$METRICS_DIR"
cat > "$METRICS_DIR/.backup.prom.tmp" <<PROM
# HELP talkcal_backup_last_success_timestamp_seconds 最後一次備份成功傳上 S3 的時間（Unix 秒）
# TYPE talkcal_backup_last_success_timestamp_seconds gauge
talkcal_backup_last_success_timestamp_seconds $(date -u +%s)
PROM
mv "$METRICS_DIR/.backup.prom.tmp" "$METRICS_DIR/backup.prom"
echo "[backup-to-s3] $reason：完成 → $dest$(basename "$dump")"
