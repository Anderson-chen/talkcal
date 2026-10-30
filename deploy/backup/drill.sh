#!/usr/bin/env bash
# 還原演練（#45）：在一組獨立的容器上跑「備份 → 誤刪 → 還原」，量時間，再跑反例證明壞掉的備份會被擋下。
# 不碰正在用的 talkcal-postgres：另外起 talkcaldrill-postgres（15432）和一個假 app，跑完全部刪掉。
#
#   bash deploy/backup/drill.sh
#
# 結果（時間軸、每一步的輸出）就是 docs/drills/2026-10-19-restore-drill.md 的來源。
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
net=talkcaldrill
pg=talkcaldrill-postgres
app=talkcaldrill-app
# 跟 deploy/compose.yaml 同一個版本：從那裡讀，compose 升版時演練跟著升
image=$(grep -m1 -oE 'postgres:[0-9.]+-alpine' "$repo/deploy/compose.yaml")   || { echo "deploy/compose.yaml 裡找不到 postgres:<版本>-alpine 的映像檔，改這行的規則" >&2; exit 1; }
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
late() { DB="${1:-talkcal}" sql -c "SELECT count(*) FROM calendar_assistant_message WHERE content = '備份之後才寫的'"; }
counts() { sql -c "SELECT (SELECT count(*) FROM account) || ' accounts, ' || (SELECT count(*) FROM calendar_event) || ' events, ' || (SELECT count(*) FROM calendar_assistant_message) || ' messages'"; }
# 反例：指令應該失敗。成功了就是檢查沒擋到，演練本身失敗
expect_fail() {
  if "$@" > /dev/null 2>"$BACKUP_DIR/err.txt"; then echo "✗ 預期失敗卻成功了：$*"; exit 1; fi
  echo "✓ 擋下來了：$(grep -E '停止|error|Error' "$BACKUP_DIR/err.txt" | head -2 | tr '\n' ' ')"
}
# 檢查：條件成立印 ✓，不成立印 ✗ 並讓整個演練失敗
check() { local msg=$1; shift; if "$@"; then echo "✓ $msg"; else echo "✗ $msg"; exit 1; fi; }
# 給反例造雜湊檔：雜湊是對著這個（壞掉的）檔案算的，所以雜湊本身會過
sum() { ( cd "$BACKUP_DIR" && sha256sum "$1" > "$1.sha256" ); }
wait_pg() { until docker exec "$pg" pg_isready -U talkcal -d talkcal -q 2>/dev/null; do sleep 1; done; }
cleanup() { docker rm -fv "$app" "$pg" > /dev/null 2>&1 || true; docker network rm "$net" > /dev/null 2>&1 || true; }
trap cleanup EXIT

step "準備：起 $pg（15432）和假 app，跑 backend 的 Flyway migration"
cleanup
docker network create "$net" > /dev/null
docker run -d --name "$pg" --network "$net" -p 127.0.0.1:15432:5432 \
  -e POSTGRES_DB=talkcal -e POSTGRES_USER=talkcal -e POSTGRES_PASSWORD=drill "$image" > /dev/null
wait_pg
sleep 2   # 官方映像檔初始化時會重啟一次伺服器，等它真的穩定
migrations="$repo/backend/src/main/resources/db/migration"
# 照 Flyway 的版本順序跑（V2 在 V10 前面：sort -V 才排得對）
for f in $(ls "$migrations" | sort -V); do
  sql -q -1 < "$migrations/$f"   # -1：一個檔一個交易，跟 Flyway 一樣
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
KEEP_SCRATCH=1 bash "$here/pg-restore.sh" "$dump" --verify-only
# 反例（不誤報）：剛備份完，備份和線上應該完全一樣
check "pg-diff：剛備份完沒有差異" bash "$here/pg-diff.sh" talkcal_restore talkcal
DB=postgres sql -q -c "DROP DATABASE talkcal_restore"

step "備份之後又寫進一則對話、改了一個帳號的名字、建了一張表（還原會讓它們從線上消失：這就是 RPO）"
sql -q -c "INSERT INTO calendar_assistant_message (owner_id, conversation_id, position, type, content) VALUES (md5('acct1')::uuid, md5('after')::uuid, 0, 'USER', '備份之後才寫的')"
sql -q -c "UPDATE account SET display_name = '備份之後改的名字' WHERE username = 'user1'"
# 備份之後才建的表（像新的 migration），而且沒有主鍵、有三列一模一樣：pg-diff 要比到它、也要算成 3 列
sql -q -c "CREATE TABLE drill_after_backup (v int); INSERT INTO drill_after_backup VALUES (1), (1), (1)"

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
check "指紋跟備份當下一樣" [ "$before" = "$after" ]
sleep 3
echo "假 app 狀態：$(docker inspect -f '{{.State.Status}}' "$app")，連線數：$(sql -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'talkcal' AND application_name = 'psql' AND pid <> pg_backend_pid()")"
old=$(DB=postgres sql -c "SELECT datname FROM pg_database WHERE datname LIKE 'talkcal_before_%'")
echo "被換下來的庫：$old"
DB=postgres sql -q -c "ALTER DATABASE $old ALLOW_CONNECTIONS true"
echo "線上有沒有備份之後才寫的那則：$(late) 則（RPO 內的資料，還原會失去）"
echo "被換下來的庫裡有沒有：$(late "$old") 則（還撈得回來）"

step "情境 C（runbook）：備份之後變了什麼 —— pg-diff.sh 備份的內容（現在的 talkcal）→ 被換下來的庫"
set +e; diff_out=$(SAMPLE=1 bash "$here/pg-diff.sh" talkcal "$old"); diff_rc=$?; set -e
echo "$diff_out"
check "結束碼 3（有差異）" [ "$diff_rc" = 3 ]
check "找到誤刪的 20000 筆行程" grep -q 'calendar_event .*新增 0、刪除 20000、改過 0' <<< "$diff_out"
check "找到備份之後多寫的 1 則對話" grep -q 'calendar_assistant_message .*新增 1、刪除 0、改過 0' <<< "$diff_out"
check "找到備份之後改過的 1 個帳號" grep -q 'account .*新增 0、刪除 0、改過 1' <<< "$diff_out"
check "找到備份之後才建的表、重複的 3 列沒被合併" grep -q 'drill_after_backup .*新增 3、刪除 0、改過 0' <<< "$diff_out"
check "沒動的表不誤報" grep -q 'account_identity .*一樣' <<< "$diff_out"

step "情境 C（runbook）：從被換下來的庫只撈回備份之後才寫的那則，線上已有的不重複"
docker exec "$pg" sh -c "pg_dump -U talkcal -d $old --data-only --inserts --on-conflict-do-nothing -t calendar_assistant_message | psql -U talkcal -d talkcal -q -v ON_ERROR_STOP=1" > /dev/null
echo "$(counts)"
check "撈回來了，其他 5000 則沒有重複" [ "$(late)" = 1 ] 
before=$(fingerprint)   # 之後的反例拿這個當基準：線上資料不能再變

step "反例 1：備份檔中間被改了一個 byte → 雜湊擋下"
bad="$BACKUP_DIR/flipped.dump"
# 先算雜湊、再改內容：模擬「備份完好，傳輸途中壞了」
cp "$dump" "$bad"; sum flipped.dump
size=$(wc -c < "$bad" | tr -d ' ')
printf '\xff' | dd of="$bad" bs=1 seek=$(( size / 2 )) conv=notrunc status=none
expect_fail bash "$here/pg-restore.sh" "$bad"

step "反例 2：備份檔只剩前半截（雜湊是截斷後才算的，所以雜湊會過）→ 暫存庫還原那步擋下"
cut="$BACKUP_DIR/truncated.dump"
head -c $(( size / 2 )) "$dump" > "$cut"; sum truncated.dump
echo "目錄讀得出來嗎：$(docker exec -i "$pg" pg_restore --list < "$cut" > /dev/null 2>&1 && echo 讀得出來（只看目錄擋不到） || echo 讀不出來)"
expect_fail bash "$here/pg-restore.sh" "$cut"

step "反例 3：沒有雜湊檔 → 不還原"
cp "$dump" "$BACKUP_DIR/nosum.dump"
expect_fail bash "$here/pg-restore.sh" "$BACKUP_DIR/nosum.dump"

step "反例 4：拿錯檔案（不是 pg_dump 的檔）→ 目錄那步擋下"
echo "SELECT 1;" > "$BACKUP_DIR/wrong.dump"; sum wrong.dump
expect_fail bash "$here/pg-restore.sh" "$BACKUP_DIR/wrong.dump"

step "反例 5：資料庫沒在跑時備份 → 失敗，而且不留下半成品"
ls_before=$(ls -A "$BACKUP_DIR" | sort)
docker stop "$pg" > /dev/null
expect_fail bash "$here/pg-backup.sh"
check "備份資料夾沒有多出任何檔案" [ "$ls_before" = "$(ls -A "$BACKUP_DIR" | sort)" ]
docker start "$pg" > /dev/null
wait_pg

step "反例都跑完：線上的資料沒被任何一個壞備份動到"
final=$(fingerprint)
check "指紋還是 $final（線上資料沒變）" [ "$final" = "$before" ]

step "退回（runbook「還原之後發現選錯備份」）：換回被換下來的那份"
docker stop "$app" > /dev/null
# 模擬 postgres-exporter：app 停了還有別的連線連著 talkcal。runbook 的 SQL 不先踢掉它的話，改名會失敗
docker exec -d "$pg" psql -U talkcal -d talkcal -c "SELECT pg_sleep(300)"
sleep 1
DB=postgres sql -q > /dev/null <<SQL
ALTER DATABASE talkcal ALLOW_CONNECTIONS false;
SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = 'talkcal' AND pid <> pg_backend_pid();
BEGIN;
ALTER DATABASE talkcal RENAME TO talkcal_wrong_restore;
ALTER DATABASE $old RENAME TO talkcal;
COMMIT;
SQL
docker start "$app" > /dev/null
echo "$(counts)"
check "回到還原之前（誤刪後）的樣子：退回的步驟可行" [ "$(sql -c "SELECT count(*) FROM calendar_event")" = 0 ]

step "全部通過，清掉演練容器"
