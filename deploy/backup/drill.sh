#!/usr/bin/env bash
# 還原演練（#45）：在一組獨立的容器上跑「備份 → 誤刪 → 還原」，量時間，再跑反例證明壞掉的備份會被擋下。
# 不碰正在用的 talkcal-postgres：另外起 talkcaldrill-postgres（15432）和一個假 app，跑完全部刪掉。
#
#   bash deploy/backup/drill.sh
#
# 結果（時間軸、每一步的輸出）就是 docs/evidence/2026-10-19-restore-drill.md 那份紀錄的來源。
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
net=talkcaldrill
pg=talkcaldrill-postgres
app=talkcaldrill-app
image=postgres:18.6-alpine           # 跟 deploy/compose.yaml 同一個版本
export BACKUP_DIR="${BACKUP_DIR:-$(mktemp -d)}"
export PG_CONTAINER=$pg APP_CONTAINER=$app

t0=$(date +%s)
step() { printf '\n[%4ss] ── %s\n' "$(( $(date +%s) - t0 ))" "$*"; }
sql() { docker exec -i "$pg" psql -X -At -v ON_ERROR_STOP=1 -U talkcal -d "${DB:-talkcal}" "$@"; }
# 整個資料庫內容的指紋：每張表依主鍵排序後整列轉文字、算 md5。還原前後一樣，就是資料一筆不差地回來了
fingerprint() {
  sql <<'SQL'
SELECT md5(string_agg(t, '|' ORDER BY t)) FROM (
  SELECT 'account:'    || md5(string_agg(a::text, ',' ORDER BY id)) AS t FROM account a
  UNION ALL SELECT 'event:'      || md5(string_agg(e::text, ',' ORDER BY id)) FROM calendar_event e
  UNION ALL SELECT 'message:'    || md5(string_agg(m::text, ',' ORDER BY owner_id, conversation_id, position)) FROM calendar_assistant_message m
) x;
SQL
}
counts() { sql -c "SELECT (SELECT count(*) FROM account) || ' accounts, ' || (SELECT count(*) FROM calendar_event) || ' events, ' || (SELECT count(*) FROM calendar_assistant_message) || ' messages'"; }
# 反例：指令應該失敗。成功了就是檢查沒擋到，演練本身失敗
expect_fail() {
  if "$@" > /dev/null 2>"$BACKUP_DIR/err.txt"; then echo "✗ 預期失敗卻成功了：$*"; exit 1; fi
  echo "✓ 擋下來了：$(grep -E '停止|error|Error' "$BACKUP_DIR/err.txt" | head -2 | tr '\n' ' ')"
}
cleanup() { docker rm -fv "$app" "$pg" > /dev/null 2>&1 || true; docker network rm "$net" > /dev/null 2>&1 || true; }
trap cleanup EXIT

step "準備：起 $pg（15432）和假 app，跑 backend 的 Flyway migration"
cleanup
docker network create "$net" > /dev/null
docker run -d --name "$pg" --network "$net" -p 127.0.0.1:15432:5432 \
  -e POSTGRES_DB=talkcal -e POSTGRES_USER=talkcal -e POSTGRES_PASSWORD=drill "$image" > /dev/null
until docker exec "$pg" pg_isready -U talkcal -d talkcal -q 2>/dev/null; do sleep 1; done
sleep 2   # 官方映像檔初始化時會重啟一次伺服器，等它真的穩定
migrations="$repo/backend/src/main/resources/db/migration"
# 照 Flyway 的版本順序跑（V2 在 V10 前面：sort -V 才排得對）
for f in $(ls "$migrations" | sort -V); do
  sql -q < "$migrations/$f"
done
echo "migration：$(ls "$migrations" | sort -V | paste -sd' ')"
# 假 app：跟真的 app 一樣一直握著連線、斷了就重連。還原那一步要能把它停掉、踢掉它的連線。
# 收到 SIGTERM 馬上結束（真的 app 也會處理 SIGTERM，只是要等手上的請求做完）：不處理的話 docker stop 要白等 10 秒才 SIGKILL
docker run -d --name "$app" --network "$net" -e PGPASSWORD=drill "$image" \
  sh -c 'trap "exit 0" TERM; while true; do psql -h talkcaldrill-postgres -U talkcal -d talkcal -c "SELECT pg_sleep(30)" >/dev/null 2>&1 & wait $!; sleep 1; done' > /dev/null

step "種資料：50 個帳號、每人 400 筆行程、100 則對話"
sql -q <<'SQL'
INSERT INTO account (id, username, password_hash, display_name)
SELECT md5('acct' || i)::uuid, 'user' || i, '{bcrypt}x', '使用者 ' || i FROM generate_series(1, 50) i;
INSERT INTO calendar_event (id, title, start_at, end_at, owner_id, category, location, note)
SELECT md5('ev' || i)::uuid, '行程 ' || i,
       timestamp '2026-01-01' + i * interval '37 minutes',
       timestamp '2026-01-01' + i * interval '37 minutes' + interval '1 hour',
       md5('acct' || (i % 50 + 1))::uuid,
       (ARRAY['WORK','PERSONAL','HEALTH','SOCIAL'])[i % 4 + 1],
       CASE WHEN i % 3 = 0 THEN '台北' END, CASE WHEN i % 5 = 0 THEN '備註 ' || i END
FROM generate_series(1, 20000) i;
INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content)
SELECT md5('acct' || (i % 50 + 1))::uuid, md5('conv' || (i % 50))::uuid, i / 50,
       CASE WHEN (i / 50) % 2 = 0 THEN 'USER' ELSE 'ASSISTANT' END, '對話內容 ' || i
FROM generate_series(0, 4999) i;
SQL
echo "$(counts)"
before=$(fingerprint)
echo "指紋：$before"

step "備份（pg-backup.sh）"
b0=$(date +%s)
dump=$(bash "$here/pg-backup.sh")
echo "備份花了 $(( $(date +%s) - b0 )) 秒：$(basename "$dump")，$(wc -c < "$dump" | tr -d ' ') bytes"
cat "$dump.sha256"

step "演練還原（--verify-only）：證明這份備份還原得回來，線上不動"
bash "$here/pg-restore.sh" "$dump" --verify-only

step "備份之後又寫進一則對話（還原會讓它從線上消失：這就是 RPO）"
sql -q -c "INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content) VALUES (md5('acct1')::uuid, md5('after')::uuid, 0, 'USER', '備份之後才寫的')"

step "事故：誤刪 —— DELETE 忘了加 WHERE"
incident=$(date +%s)
sql -c "DELETE FROM calendar_event" | sed 's/^/刪掉 /'
echo "$(counts)"

step "還原（pg-restore.sh，真的換掉線上）"
r0=$(date +%s)
bash "$here/pg-restore.sh" "$dump"
r1=$(date +%s)
echo "還原指令花了 $(( r1 - r0 )) 秒；從誤刪到資料回來共 $(( r1 - incident )) 秒"

step "驗證：資料一筆不差地回來、假 app 又連上了、被換下來的庫還在"
echo "$(counts)"
after=$(fingerprint)
echo "指紋：$after"
[ "$before" = "$after" ] && echo "✓ 指紋跟備份當下一樣" || { echo "✗ 指紋不一樣"; exit 1; }
sleep 3
echo "假 app 狀態：$(docker inspect -f '{{.State.Status}}' "$app")，連線數：$(sql -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'talkcal' AND application_name = 'psql' AND pid <> pg_backend_pid()")"
old=$(DB=postgres sql -c "SELECT datname FROM pg_database WHERE datname LIKE 'talkcal_before_%'")
echo "被換下來的庫：$old"
DB=postgres sql -q -c "ALTER DATABASE $old ALLOW_CONNECTIONS true"
echo "線上有沒有備份之後才寫的那則：$(sql -c "SELECT count(*) FROM calendar_assistant_message WHERE content = '備份之後才寫的'") 則（RPO 內的資料，還原會失去）"
echo "被換下來的庫裡有沒有：$(DB=$old sql -c "SELECT count(*) FROM calendar_assistant_message WHERE content = '備份之後才寫的'") 則（還撈得回來）"

step "情境 C（runbook）：從被換下來的庫只撈回備份之後才寫的那則，線上已有的不重複"
docker exec "$pg" sh -c "pg_dump -U talkcal -d $old --data-only --inserts --on-conflict-do-nothing -t calendar_assistant_message | psql -U talkcal -d talkcal -q -v ON_ERROR_STOP=1" > /dev/null
echo "$(counts)"
[ "$(sql -c "SELECT count(*) FROM calendar_assistant_message WHERE content = '備份之後才寫的'")" = 1 ]   && echo "✓ 撈回來了，其他 5000 則沒有重複" || { echo "✗ 沒撈回來"; exit 1; }
before=$(fingerprint)   # 之後的反例拿這個當基準：線上資料不能再變

step "反例 1：備份檔中間被改了一個 byte → 雜湊擋下"
bad="$BACKUP_DIR/flipped.dump"
cp "$dump" "$bad"; ( cd "$BACKUP_DIR" && sha256sum "$(basename "$dump")" | sed "s/$(basename "$dump")/flipped.dump/" > flipped.dump.sha256 )
size=$(wc -c < "$bad" | tr -d ' ')
printf '\xff' | dd of="$bad" bs=1 seek=$(( size / 2 )) conv=notrunc status=none
expect_fail bash "$here/pg-restore.sh" "$bad"

step "反例 2：備份檔只剩前半截（雜湊是截斷後才算的，所以雜湊會過）→ 暫存庫還原那步擋下"
cut="$BACKUP_DIR/truncated.dump"
head -c $(( size / 2 )) "$dump" > "$cut"; ( cd "$BACKUP_DIR" && sha256sum truncated.dump > truncated.dump.sha256 )
echo "目錄讀得出來嗎：$(docker exec -i "$pg" pg_restore --list < "$cut" > /dev/null 2>&1 && echo 讀得出來（只看目錄擋不到） || echo 讀不出來)"
expect_fail bash "$here/pg-restore.sh" "$cut"

step "反例 3：沒有雜湊檔 → 不還原"
cp "$dump" "$BACKUP_DIR/nosum.dump"
expect_fail bash "$here/pg-restore.sh" "$BACKUP_DIR/nosum.dump"

step "反例 4：拿錯檔案（不是 pg_dump 的檔）→ 目錄那步擋下"
echo "SELECT 1;" > "$BACKUP_DIR/wrong.dump"; ( cd "$BACKUP_DIR" && sha256sum wrong.dump > wrong.dump.sha256 )
expect_fail bash "$here/pg-restore.sh" "$BACKUP_DIR/wrong.dump"

step "反例 5：資料庫沒在跑時備份 → 失敗，而且不留下半成品"
ls_before=$(ls -A "$BACKUP_DIR" | sort)
docker stop "$pg" > /dev/null
expect_fail bash "$here/pg-backup.sh"
[ "$ls_before" = "$(ls -A "$BACKUP_DIR" | sort)" ] && echo "✓ 備份資料夾沒有多出任何檔案" || { echo "✗ 留下了檔案"; exit 1; }
docker start "$pg" > /dev/null
until docker exec "$pg" pg_isready -U talkcal -d talkcal -q 2>/dev/null; do sleep 1; done

step "反例都跑完：線上的資料沒被任何一個壞備份動到"
final=$(fingerprint)
[ "$final" = "$before" ] && echo "✓ 指紋還是 $final" || { echo "✗ 線上資料變了"; exit 1; }

step "退回（runbook「還原之後發現選錯備份」）：換回被換下來的那份"
docker stop "$app" > /dev/null
DB=postgres sql -q <<SQL
BEGIN;
ALTER DATABASE talkcal RENAME TO talkcal_wrong_restore;
ALTER DATABASE $old RENAME TO talkcal;
COMMIT;
SQL
docker start "$app" > /dev/null
echo "$(counts)"
[ "$(sql -c "SELECT count(*) FROM calendar_event")" = 0 ] && echo "✓ 回到還原之前（誤刪後）的樣子：退回的步驟可行" || { echo "✗ 沒有退回"; exit 1; }

step "全部通過，清掉演練容器"
