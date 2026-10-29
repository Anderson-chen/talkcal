#!/usr/bin/env bash
# 把 pg-backup.sh 做的備份還原回去（#45）。步驟和理由見 docs/runbooks/backup-restore.md。
#
#   bash deploy/backup/pg-restore.sh <備份檔.dump> --verify-only   # 只演練：還原到暫存庫、印筆數、刪掉暫存庫
#   bash deploy/backup/pg-restore.sh <備份檔.dump>                 # 真的還原：換掉線上的資料庫
#   KEEP_SCRATCH=1 bash deploy/backup/pg-restore.sh <備份檔.dump> --verify-only   # 演練完留著暫存庫，只撈幾筆回來用
#
# 順序是「先證明備份是好的，才動線上」：
#   1. 雜湊對得上（複製、傳輸途中沒壞）。雜湊跟備份由同一個角色寫，防得了損壞、防不了蓄意竄改
#   2. pg_restore 讀得出目錄
#   3. 整份還原到暫存庫 <db>_restore 成功（後半截不見、資料違反約束，都在這裡失敗）
#   4. 停 app → 線上庫改名成 <db>_before_<時間>（留著，不刪）→ 暫存庫改名成 <db> → 起 app
# 1～3 任何一步失敗就結束，線上的資料庫一個 byte 都沒動。
# 第 4 步只是兩次改名：出事就改回來（runbook 有指令），被換下來的那份也還能撈「備份之後才寫進去」的資料。
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-talkcal-postgres}"
PG_USER="${PG_USER:-talkcal}"
PG_DB="${PG_DB:-talkcal}"
# 還原時要先停掉的 app 容器（它握著連線，資料庫改不了名）。設成空字串就不停（演練用）
APP_CONTAINER="${APP_CONTAINER-talkcal-app}"

log() { echo "[pg-restore] $*" >&2; }
die() { log "停止：$*"; exit 1; }

dump="${1:-}"
mode="${2:-}"
[ -n "$dump" ] || die "用法：pg-restore.sh <備份檔.dump> [--verify-only]"
[ -f "$dump" ] || die "找不到備份檔：$dump"
case "$mode" in ""|--verify-only) ;; *) die "不認得的參數：$mode" ;; esac

scratch="${PG_DB}_restore"
psql_db() { local db=$1; shift; docker exec -i "$PG_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$db" "$@"; }
psql_admin() { psql_db postgres -q "$@"; }
row_counts() {
  # 每張表的筆數（精確的 count，不是統計估計值），兩邊並排給人看還原的是不是預期的那份
  psql_db "$1" -At <<'SQL'
SELECT format('%s=%s', table_name,
              (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text)
FROM information_schema.tables
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;
SQL
}

start=$(date +%s)

log "1/4 雜湊：$dump.sha256"
[ -f "$dump.sha256" ] || die "沒有雜湊檔 $dump.sha256：不知道這份備份是不是完整的，不還原"
( cd "$(dirname "$dump")" && sha256sum --quiet -c "$(basename "$dump").sha256" ) \
  || die "雜湊對不上：備份檔在複製或傳輸途中壞了，換一份"

log "2/4 目錄"
docker exec -i "$PG_CONTAINER" pg_restore --list < "$dump" > /dev/null \
  || die "pg_restore 讀不出目錄：不是 pg_dump -Fc 的檔案，或檔頭壞了"

log "3/4 還原到暫存庫 $scratch"
psql_admin -c "DROP DATABASE IF EXISTS $scratch" -c "CREATE DATABASE $scratch OWNER $PG_USER"
# --single-transaction + --exit-on-error：一個錯誤就整份撤回，不會留下一個「還原了一半」的暫存庫被誤當成好的
if ! docker exec -i "$PG_CONTAINER" pg_restore -U "$PG_USER" -d "$scratch" \
       --single-transaction --exit-on-error --no-owner < "$dump"; then
  psql_admin -c "DROP DATABASE IF EXISTS $scratch"
  die "還原到暫存庫失敗（檔案不完整或內容有錯）。線上的 $PG_DB 沒有動"
fi
log "備份裡的筆數：$(row_counts "$scratch" | tr '\n' ' ')"
log "線上目前的筆數：$(row_counts "$PG_DB" | tr '\n' ' ')"

if [ "$mode" = --verify-only ]; then
  # KEEP_SCRATCH=1：留著暫存庫，從裡面只撈某幾筆回線上（runbook 情境 C）
  if [ "${KEEP_SCRATCH:-}" = 1 ]; then
    log "暫存庫 $scratch 留著（KEEP_SCRATCH=1），用完記得 DROP"
  else
    psql_admin -c "DROP DATABASE $scratch"
  fi
  log "演練完成：這份備份還原得回來（$(( $(date +%s) - start )) 秒）。線上沒有動"
  exit 0
fi

log "4/4 換上：$PG_DB → ${PG_DB}_before_*，$scratch → $PG_DB"
if [ -n "$APP_CONTAINER" ] && [ "$(docker inspect -f '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null)" = true ]; then
  log "停 $APP_CONTAINER（它握著連線）"
  docker stop "$APP_CONTAINER" > /dev/null
  restart_app=1
fi
# 第 4 步停掉的 app 要起回來：換上成功或失敗都要
start_app() { if [ "${restart_app:-}" = 1 ]; then log "起 $APP_CONTAINER"; docker start "$APP_CONTAINER" > /dev/null; fi; }
# 資料庫名稱沒加引號會被轉成小寫，所以時間格式只用數字和底線，印出來的跟實際的名稱一樣
old="${PG_DB}_before_$(date -u +%Y%m%d_%H%M%S)"
# 先不讓新的連線進來、再踢掉剩下的（psql、postgres_exporter 之類），改名才不會撞上「有人在用」。
# 被換下來的那份維持不能連：免得哪個忘了改設定的程式繼續寫進一個已經不是線上的庫
# 兩次改名包在同一個交易：要嘛都換了、要嘛都沒換，不會出現「線上庫不見了」的中間狀態
if ! psql_admin > /dev/null <<SQL
ALTER DATABASE $PG_DB ALLOW_CONNECTIONS false;
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$PG_DB' AND pid <> pg_backend_pid();
BEGIN;
ALTER DATABASE $PG_DB RENAME TO $old;
ALTER DATABASE $scratch RENAME TO $PG_DB;
COMMIT;
SQL
then
  psql_admin -c "ALTER DATABASE $PG_DB ALLOW_CONNECTIONS true"
  start_app
  die "換上失敗，已經復原成原本的 $PG_DB（暫存庫 $scratch 還在，可以再試一次）"
fi
log "換上完成。被換下來的是 $old（確認沒問題後再 DROP，指令見 runbook）"

start_app
log "還原完成（$(( $(date +%s) - start )) 秒）"
