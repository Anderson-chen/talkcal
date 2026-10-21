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
# 舊版 user_data 寫的 deploy.env 沒有 BACKUPS_BUCKET（那時還沒有這個 bucket）：照 backups.tf 的命名規則推出來。
# get-caller-identity 不需要任何 IAM 權限
BACKUPS_BUCKET="${BACKUPS_BUCKET:-$PROJECT-backups-$(aws sts get-caller-identity --region "$AWS_REGION" --query Account --output text)}"

ROOT="/opt/$PROJECT"
export BACKUP_DIR="$ROOT/backups" KEEP_DAYS=2 KEEP_MIN=3
export PG_CONTAINER="$PROJECT-postgres"

echo "[backup-to-s3] $reason：開始"
dump="$(bash "$ROOT/repo/deploy/backup/pg-backup.sh")"

# 先傳 dump、再傳雜湊檔：S3 上看得到 .sha256，就代表它的 dump 已經完整傳上去了
dest="s3://$BACKUPS_BUCKET/postgres/"
aws s3 cp "$dump" "$dest" --region "$AWS_REGION" --only-show-errors
aws s3 cp "$dump.sha256" "$dest" --region "$AWS_REGION" --only-show-errors

# 最後一次成功的時間。告警（#152 合併後）可以拿它判斷「太久沒備份」；現在先給人用 cat 看
date -u +%Y-%m-%dT%H:%M:%SZ > "$BACKUP_DIR/last-success"
echo "[backup-to-s3] $reason：完成 → $dest$(basename "$dump")"
