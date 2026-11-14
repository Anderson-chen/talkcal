#!/usr/bin/env bash
# 備份 PostgreSQL：pg_dump 成一個檔案，驗過才保留，再清掉過期的舊檔。本機（Git Bash）和 EC2 都用這支。
#
#   bash deploy/backup/pg-backup.sh                       # 備份 talkcal-postgres 到 ~/talkcal-backups
#   BACKUP_DIR=/opt/talkcal/backups bash deploy/backup/pg-backup.sh
#
# stdout 只印一行：備份檔的完整路徑，給呼叫的腳本接著用；其他訊息寫 stderr。
# 為什麼用 pg_dump、不用 volume 快照或 WAL 歸檔：見 docs/runbooks/backup-restore.md。
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-talkcal-postgres}"
PG_USER="${PG_USER:-talkcal}"
PG_DB="${PG_DB:-talkcal}"
# 不放 repo 裡：刪 worktree、git clean 會把備份一起帶走。也不放 Docker volume：down -v 會跟資料庫一起清掉
BACKUP_DIR="${BACKUP_DIR:-$HOME/talkcal-backups}"
# 超過 KEEP_DAYS 天的刪掉，但最新的 KEEP_MIN 份不管多舊都留。只看天數的話，
# 機器關了一個月再開，第一次備份完就把之前的全刪光，偏偏那時最可能要回頭找
KEEP_DAYS="${KEEP_DAYS:-14}"
KEEP_MIN="${KEEP_MIN:-7}"

log() { echo "[pg-backup] $*" >&2; }

# 備份裡有所有人的行程和對話
umask 077
mkdir -p "$BACKUP_DIR"
# 檔名只到秒：前一份在同一秒剛做完的話，等到下一秒，不蓋掉前一份
while name="$PG_DB-$(date -u +%Y%m%dT%H%M%SZ).dump"; [ -e "$BACKUP_DIR/$name" ]; do sleep 1; done
final="$BACKUP_DIR/$name"
tmp="$BACKUP_DIR/.$name.tmp"
# 中途失敗只會留下 .tmp，結束時清掉：目錄裡的 .dump 都是完整做完、驗過的
trap 'rm -f "$tmp"' EXIT

start=$(date +%s)
log "pg_dump $PG_CONTAINER/$PG_DB → $final"
# -Fc：有壓縮、能只還原某幾張表，pg_restore 也讀得出目錄（下面的檢查靠這個）。
# 在容器裡執行：pg_dump 跟伺服器同版，主機不必裝用戶端；走 local socket（官方映像檔設成 trust），不用帶密碼
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc > "$tmp"

# 檢查一：讀得出目錄。擋得到不是 dump 檔、檔頭壞了、空檔案；
# 擋不到後半截不見（目錄在檔頭），那要靠 pg-restore.sh --verify-only 整份還原一次
if ! toc=$(docker exec -i "$PG_CONTAINER" pg_restore --list < "$tmp"); then
  log "備份檔讀不出目錄，不保留：$tmp"
  exit 1
fi
entries=$(grep -c ' TABLE DATA ' <<< "$toc" || true)

mv "$tmp" "$final"
# 檢查二：雜湊。之後複製、上傳、下載途中壞掉，還原前一比就知道
( cd "$BACKUP_DIR" && sha256sum "$name" > "$name.sha256" )

size=$(wc -c < "$final" | tr -d ' ')
log "完成：${size} bytes、${entries} 張表的資料、$(( $(date +%s) - start )) 秒"

# 檔名是 UTC 時間，字典序就是時間順序
mapfile -t dumps < <(find "$BACKUP_DIR" -maxdepth 1 -name "$PG_DB-*.dump" | sort -r)
for (( i = KEEP_MIN; i < ${#dumps[@]}; i++ )); do
  f="${dumps[$i]}"
  if [ -n "$(find "$f" -mtime +"$KEEP_DAYS")" ]; then
    log "過期刪除：$(basename "$f")"
    rm -f "$f" "$f.sha256"
  fi
done

echo "$final"
