#!/usr/bin/env bash
# 檢查兩個環境的 compose 設定有沒有共用到「會互相干擾」的東西（#24）。
#
#   cd/check-isolation.sh                    # 預設比 local 和 staging
#   cd/check-isolation.sh local staging
#
# 怎麼比：用 cd/envs/<env>.env 同一套參數（疊哪幾份 compose、讀哪個 .env）跑 docker compose config，
# 拿 compose 合併、代換完的結果來比，不是比原始檔 —— 疊加層漏蓋一個欄位，原始檔看不出來，合併後才看得出來。
#
# 共用了就失敗的：
#   專案名稱      同名的話 up 會直接換掉另一個環境的容器
#   主機埠        後起的那個起不來，或更糟：打到另一個環境
#   container_name、網路、volume  同一個名字就是同一個東西：volume 共用 = 資料庫共用
#   自己 build 的映像檔 tag  build staging 會把正式環境的 tag 指到還沒驗證的版本
#   DB 密碼       不印出來，只比是否相同
# 刻意允許共用的：拉來的映像檔（postgres:18.6-alpine，唯讀），和 staging 透過主機的 8080 用正式的模型（見 deploy/compose.staging.yaml）。
#
# .env 還沒建（例如在乾淨的 clone 裡）就用 deploy/.env.example 填假值代替：比的是結構，不是祕密本身。
# 只讀設定、不起任何容器。
set -euo pipefail
cd "$(dirname "$0")/.."

a="${1:-local}"
b="${2:-staging}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 印出某個環境合併後的設定（normalized YAML）
render() {
  local env_name="$1"
  (
    # shellcheck source=/dev/null
    source "cd/envs/$env_name.env"
    local args=() env_file="${COMPOSE_ENV_FILE:-deploy/.env}"
    for f in $COMPOSE_FILES; do args+=(-f "$f"); done
    if [ ! -f "$env_file" ]; then
      echo "（$env_name：沒有 $env_file，用 .env.example 填假值）" >&2
      env_file="$tmp/$env_name.env"
      sed "s/^DB_PASSWORD=.*/DB_PASSWORD=placeholder-$env_name/" deploy/.env.example > "$env_file"
      # staging 的 compose 要求這個變數（防止誤用正式的 .env），照 deploy/README.md 的步驟補上
      echo "TALKCAL_ENV=$env_name" >> "$env_file"
    fi
    docker compose "${args[@]}" --env-file "$env_file" config
  )
}

# 從 normalized YAML 抽出要比的東西，一行一個「種類 值」。
# compose config 的輸出縮排固定（頂層 0、服務 2、欄位 4），所以用縮排判斷在哪一段就夠了
extract() {
  awk '
    /^[a-z]/ { top = $1; sub(":", "", top) }
    /^name:/ { print "專案名稱", $2 }
    top == "services" && /^  [^ ]/ { svc = $1; sub(":", "", svc) }
    top == "services" && /^    build:/ { built[svc] = 1 }
    top == "services" && /^    image:/ { image[svc] = $2 }
    top == "services" && /^    container_name:/ { print "container_name", $2 }
    top == "services" && /^        published:/ { gsub("\"", "", $2); print "主機埠", $2 }
    top == "services" && /^      POSTGRES_PASSWORD:/ { print "DB密碼", $2 }
    (top == "networks" || top == "volumes") && /^    name:/ { print top, $2 }
    END { for (s in built) if (s in image) print "自己build的映像檔", image[s] }
  ' | sort -u
}

render "$a" | extract > "$tmp/a.txt"
render "$b" | extract > "$tmp/b.txt"

shared="$(comm -12 "$tmp/a.txt" "$tmp/b.txt")"
if [ -n "$shared" ]; then
  echo "❌ $a 和 $b 共用了：" >&2
  # 密碼只說「一樣」，不印值
  sed -E 's/^(DB密碼) .*/\1（兩邊相同）/' <<<"$shared" | sed 's/^/   /' >&2
  exit 1
fi

echo "✅ $a 和 $b 沒有共用的專案名稱、埠、容器、網路、volume、自己 build 的映像檔、DB 密碼"
for env_name in "$a" "$b"; do
  f="$tmp/a.txt"; [ "$env_name" = "$b" ] && f="$tmp/b.txt"
  echo "── $env_name"
  grep -v '^DB密碼' "$f" | sed 's/^/   /'
done
