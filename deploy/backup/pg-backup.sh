#!/usr/bin/env bash
# 備份 PostgreSQL：pg_dump 成一個檔案，驗過完整性才算數，再清掉過期的舊檔（#45）。
# 本機（Git Bash）和 EC2 共用這一支；EC2 上由 infra/envs/1-ec2/backup-to-s3.sh 呼叫，再把結果傳上 S3。
#
#   bash deploy/backup/pg-backup.sh                       # 備份 talkcal-postgres 到 ~/talkcal-backups
#   BACKUP_DIR=/opt/talkcal/backups bash deploy/backup/pg-backup.sh
#
# 成功時最後一行印出備份檔的完整路徑（給呼叫它的腳本接著用），其他訊息都寫到 stderr。
# 為什麼用 pg_dump、不用 volume 快照或 WAL 歸檔：理由在 docs/runbooks/backup-restore.md。
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-talkcal-postgres}"
PG_USER="${PG_USER:-talkcal}"
PG_DB="${PG_DB:-talkcal}"
# 預設放在 repo 外面：放在 repo 裡的話，刪 worktree、git clean 都可能把備份一起帶走（#103 掉過檔案）。
# 也不放在 Docker volume 裡：`docker compose down -v` 會連同資料庫一起清掉，備份就跟著沒了
BACKUP_DIR="${BACKUP_DIR:-$HOME/talkcal-backups}"
# 保留規則：超過 KEEP_DAYS 天的刪掉，但最新的 KEEP_MIN 份無論多舊都留著。
# 只看天數的話，機器關了一個月再開，第一次備份完就會把之前的全刪光 —— 剛好是最可能要回頭找的時候
KEEP_DAYS="${KEEP_DAYS:-14}"
KEEP_MIN="${KEEP_MIN:-7}"

log() { echo "[pg-backup] $*" >&2; }

mkdir -p "$BACKUP_DIR"
name="$PG_DB-$(date -u +%Y%m%dT%H%M%SZ).dump"
final="$BACKUP_DIR/$name"
tmp="$BACKUP_DIR/.$name.tmp"
# 中途失敗（容器沒在跑、磁碟滿、被中斷）只會留下 .tmp，而且會被清掉：
# 目錄裡看得到的 .dump 一定是完整跑完、驗過的那種
trap 'rm -f "$tmp"' EXIT

start=$(date +%s)
log "pg_dump $PG_CONTAINER/$PG_DB → $final"
# -Fc（custom 格式）：自帶壓縮、可以只還原某幾張表、pg_restore 讀得懂它的目錄（下面的完整性檢查靠這個）。
# 在容器裡跑 pg_dump：版本一定跟伺服器一樣，主機上也不必裝 PostgreSQL 用戶端。
# 走容器裡的 local socket（官方映像檔對它是 trust），不必把密碼帶進來
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc > "$tmp"

# 完整性檢查一：pg_restore 讀得出目錄。擋的是「不是 dump 檔」「檔頭壞了」「空檔案」。
# 擋不到「後半截不見」（目錄在檔頭），那種要靠還原演練（pg-restore.sh --verify-only）才抓得到
if ! toc=$(docker exec -i "$PG_CONTAINER" pg_restore --list < "$tmp"); then
  log "備份檔讀不出目錄，不保留：$tmp"
  exit 1
fi
entries=$(grep -c ' TABLE DATA ' <<< "$toc" || true)

mv "$tmp" "$final"
# 完整性檢查二：雜湊。之後複製、上傳、下載途中壞掉（或被改過），還原前一比就知道
( cd "$BACKUP_DIR" && sha256sum "$name" > "$name.sha256" )

size=$(wc -c < "$final" | tr -d ' ')
log "完成：${size} bytes、${entries} 張表的資料、$(( $(date +%s) - start )) 秒"

# 保留：檔名帶 UTC 時間，字典序就是時間順序
mapfile -t dumps < <(find "$BACKUP_DIR" -maxdepth 1 -name "$PG_DB-*.dump" | sort -r)
for (( i = KEEP_MIN; i < ${#dumps[@]}; i++ )); do
  f="${dumps[$i]}"
  if [ -n "$(find "$f" -mtime +"$KEEP_DAYS")" ]; then
    log "過期刪除：$(basename "$f")"
    rm -f "$f" "$f.sha256"
  fi
done

echo "$final"
