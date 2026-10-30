#!/usr/bin/env bash
# 比兩個資料庫差在哪：備份那一刻之後，到現在多了、少了、改了哪些列（#45，runbook 情境 C）。
#
#   bash deploy/backup/pg-diff.sh <舊的庫> <新的庫> [表 ...]
#
#   還原前：KEEP_SCRATCH=1 pg-restore.sh <檔> --verify-only 留下 talkcal_restore（備份的內容），
#           bash deploy/backup/pg-diff.sh talkcal_restore talkcal
#   還原後：bash deploy/backup/pg-diff.sh talkcal talkcal_before_<時間>（現在的 talkcal 是備份的內容）
#
# 每張表依主鍵對齊，分成三種：新增（只在新的庫）、刪除（只在舊的庫）、改過（主鍵一樣、內容不一樣）。
# 沒有主鍵的表只能整列比：改過的列會變成一筆刪除加一筆新增（重複的列照數量算）。
# 只讀：兩邊都只跑 SELECT，不建表、不裝擴充。表不給就比 public 底下全部的表。
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-talkcal-postgres}"
PG_USER="${PG_USER:-talkcal}"
# 每一類最多印幾筆內容。筆數一律照實算，只是內容不全印
SAMPLE="${SAMPLE:-5}"

old="${1:-}"; new="${2:-}"
[ -n "$old" ] && [ -n "$new" ] || { echo "用法：pg-diff.sh <舊的庫> <新的庫> [表 ...]" >&2; exit 1; }
shift 2

q() { docker exec -i "$PG_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$1" -c "$2"; }

list_tables() { q "$1" "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"; }
tables=("$@")
if [ ${#tables[@]} -eq 0 ]; then
  # 兩邊的表合起來：備份之後才建的表（新的 migration）只在新的庫裡，也要比到
  mapfile -t tables < <({ list_tables "$old"; list_tables "$new"; } | sort -u)
fi

# 一張表的每一列寫成一行：主鍵、整列內容的 md5、整列內容。COPY 的文字格式會把換行、tab 跳脫掉，一列一定是一行。
# 表不在這個庫裡（例如之後才建的）就寫空檔：整張算新增或刪除
dump() {  # <庫> <表> <主鍵運算式> <輸出檔>
  if [ -z "$(q "$1" "SELECT to_regclass('public.' || quote_ident('$2'))")" ]; then : > "$4"; return; fi
  q "$1" "COPY (SELECT $3, md5(r::text), r::text FROM public.\"$2\" r ORDER BY 1) TO STDOUT" > "$4"
}

pk_of() {  # <庫> <表>
  q "$1" "SELECT string_agg(format('%I', a.attname), ', ' ORDER BY array_position(i.indkey, a.attnum))
          FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
          WHERE i.indrelid = to_regclass('public.' || quote_ident('$2')) AND i.indisprimary"
}

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "比較：$old（舊）→ $new（新）"
total=0
for t in "${tables[@]}"; do
  # 主鍵欄位，依主鍵定義的順序。新的庫沒有這張表（備份之後被刪掉）就看舊的庫
  pk=$(pk_of "$new" "$t")
  [ -n "$pk" ] || pk=$(pk_of "$old" "$t")
  # 沒有主鍵就拿整列的 md5 當鍵，再加上同樣內容的第幾列：一模一樣的重複列才不會在比對時被合併成一列
  key="${pk:+concat_ws('|', $pk)}"
  key="${key:-md5(r::text) || '#' || row_number() OVER (PARTITION BY md5(r::text))}"
  dump "$old" "$t" "$key" "$work/old"
  dump "$new" "$t" "$key" "$work/new"

  awk -F'\t' -v t="$t" -v n="$SAMPLE" '
    # 用檔名分辨兩邊，不用 NR == FNR：舊的那份是空檔時，NR == FNR 在讀新的那份時也成立，新增會被當成刪除
    FILENAME == ARGV[1] { h[$1] = $2; row[$1] = $3; next }
    !($1 in h)     { add[++na] = $3; next }
    h[$1] != $2    { chg[++nc] = row[$1] " → " $3 }
                   { delete h[$1] }
    END {
      for (k in h) del[++nd] = row[k]
      if (na + nd + nc == 0) { printf "  %-28s 一樣\n", t; exit }
      printf "  %-28s 新增 %d、刪除 %d、改過 %d\n", t, na, nd, nc
      for (i = 1; i <= na && i <= n; i++) print "      + " substr(add[i], 1, 160)
      for (i = 1; i <= nd && i <= n; i++) print "      - " substr(del[i], 1, 160)
      for (i = 1; i <= nc && i <= n; i++) print "      ~ " substr(chg[i], 1, 320)
      exit 3
    }' "$work/old" "$work/new" || { [ $? -eq 3 ] && total=$((total + 1)) || exit 1; }
done

# 結束碼：0 = 兩邊一樣，3 = 有差異（給腳本判斷用），其他 = 出錯
if [ "$total" -gt 0 ]; then echo "$total 張表有差異"; exit 3; fi
echo "完全一樣"
