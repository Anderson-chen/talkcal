#!/usr/bin/env bash
# 零停機換 app（#20）：新版先起在「沒在接流量」的那個 slot，healthy 了才把流量切過去，再關舊的。
# 可選 canary：先只切一小部分流量，查 Mimir 的錯誤率和 p95，過了才全切；沒過就切回去、新版關掉。
#
#   cd/bluegreen.sh local                      blue/green：起新的 → 全切 → 關舊的
#   CANARY_PERCENT=10 cd/bluegreen.sh local    先切 10%，觀察 CANARY_SECONDS 秒，過了才全切
#   cd/bluegreen.sh local --rollback           切回上一個 slot（容器還在、還是舊版映像檔，不必重 build）
#   cd/bluegreen.sh local --status             現在 /api 轉給誰
#
# 兩個 slot 是 deploy/compose.yaml 的 app（blue）和 app-green；誰接流量記在 web 的 nginx 裡
# （routing volume 的 app.conf，格式見 web/nginx.conf）。為什麼這樣做、canary 怎麼判斷，見 deploy/README.md「零停機部署與 canary」。
#
# cd/apply.sh 在 env 檔設了 APP_STRATEGY=bluegreen 時，部署 app 就改走這支；也可以直接跑。

set -euo pipefail
cd "$(dirname "$0")/.."
# Windows 的 Git Bash 會把 /etc/nginx/... 這種參數自動轉成 C:/Program Files/Git/etc/...，傳給 docker 就找不到檔案。
# 只對 Git Bash 有作用，Linux（EC2）上沒有這個變數的意義
export MSYS_NO_PATHCONV=1

env_name="${1:?用法：cd/bluegreen.sh <env> [--rollback|--status]}"
action="${2:-deploy}"
env_file="cd/envs/$env_name.env"
[ -f "$env_file" ] || { echo "沒有這個環境：$env_file" >&2; exit 1; }
# shellcheck source=/dev/null
source "$env_file"
: "${COMPOSE_FILES:?$env_file 要設定 COMPOSE_FILES}"

# --profile bluegreen：app-green 平常不起（deploy/compose.yaml），這支要看得到它、啟動和停止它
compose_args=(--profile bluegreen)
# EXTRA_COMPOSE_FILES：多疊幾份，只給實驗用（例如 perf/canary/ 的示範環境、故意壞掉的新版），env 檔不設
for f in $COMPOSE_FILES ${EXTRA_COMPOSE_FILES:-}; do compose_args+=(-f "$f"); done
dc() { docker compose "${compose_args[@]}" "$@"; }

# ── 設定（都可以用環境變數蓋掉）────────────────────────────────────────
WAIT_TIMEOUT="${WAIT_TIMEOUT:-300}"      # 新版等 healthy 的上限（秒），跟 apply.sh 的 app 一樣
# 切過去之後等幾秒才關舊的：nginx -s reload 是非同步的，舊的 worker 可能還在把剛進來的請求轉給舊 slot。
# 處理中的長請求不靠這幾秒：舊 app 收到 SIGTERM 會等手上的做完（graceful shutdown，最多 2 分鐘）
DRAIN_SECONDS="${DRAIN_SECONDS:-5}"
CANARY_PERCENT="${CANARY_PERCENT:-0}"    # 0 = 不做 canary，healthy 就全切
CANARY_SECONDS="${CANARY_SECONDS:-120}"  # canary 觀察多久
CANARY_INTERVAL="${CANARY_INTERVAL:-30}" # 多久查一次指標；錯誤率超標就提早切回，不必等滿
CANARY_MIN_REQUESTS="${CANARY_MIN_REQUESTS:-20}"   # 新版至少要接到這麼多請求才判斷得了，不夠就當作沒過
CANARY_MAX_ERROR_RATE="${CANARY_MAX_ERROR_RATE:-0.01}"  # 5xx 比例上限
CANARY_MAX_P95_RATIO="${CANARY_MAX_P95_RATIO:-1.5}"     # 新版 p95 最多是舊版同一段時間的幾倍
# 而且要多出超過這麼多秒才算變慢：剛起的 JVM 還沒暖機（JIT），查行程這種幾十毫秒的請求 p95 會是舊版的好幾倍，
# 但只多了幾十毫秒、使用者感覺不到。2026-10-16 只有倍數這條時，正常的新版因為 25ms → 105ms 被擋下
CANARY_P95_SLACK="${CANARY_P95_SLACK:-0.25}"
# Mimir 的 Prometheus 相容 API（ops/compose.yaml 發佈在 9009）和選哪些指標
METRICS_URL="${METRICS_URL:-http://127.0.0.1:9009/prometheus}"
METRICS_SELECTOR="${METRICS_SELECTOR:-job=\"talkcal-app\"}"

ROUTING_FILE=/etc/nginx/routing/app.conf

log() { echo "[$(date +%H:%M:%S)] $*"; }
other() { [ "$1" = app ] && echo app-green || echo app; }
# Alloy 抓指標時給每個 slot 的標籤（ops/alloy/config.alloy）
slot_label() { [ "$1" = app ] && echo blue || echo green; }

web_running() { [ -n "$(dc ps -q --status running web 2>/dev/null)" ]; }

# 現在全部（或大部分）流量在哪個 slot：split_clients 裡 * 那一行
active_slot() {
  local line
  line=$(dc exec -T web cat "$ROUTING_FILE" 2>/dev/null | grep -E '^\s*\*' || true)
  case "$line" in
    *app-green*) echo app-green ;;
    *) echo app ;;
  esac
}

# 改寫 nginx 的 split_clients、檢查設定、reload。設定錯了就換回原本的，不讓 web 帶著壞設定
route() {
  local body
  if [ $# -eq 1 ]; then
    body="    * $1;"
    log "流量 → 全部給 $1"
  else
    body="    $2% $1;
    * $3;"
    log "流量 → $2% 給 $1（canary），其他給 $3"
  fi
  printf 'split_clients "${request_id}" $app_host {\n%s\n}\n' "$body" |
    dc exec -T web sh -c "cp $ROUTING_FILE $ROUTING_FILE.bak && cat > $ROUTING_FILE"
  if ! dc exec -T web nginx -t -q; then
    dc exec -T web cp "$ROUTING_FILE.bak" "$ROUTING_FILE"
    echo "nginx 設定檢查沒過，已換回原本的 $ROUTING_FILE" >&2
    exit 1
  fi
  dc exec -T web nginx -s reload
}

# 對 Mimir 下一個 PromQL，印出數值；沒有資料印空字串
query() {
  curl -fsS -G "$METRICS_URL/api/v1/query" --data-urlencode "query=$1" |
    sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p'
}

# 某個 slot 最近 window 內的請求數、5xx 比例、p95（秒）。/actuator 是 healthcheck 和 Alloy 自己，不算
sel() { echo "$METRICS_SELECTOR,slot=\"$(slot_label "$1")\",uri!~\"/actuator.*\""; }
requests() { query "sum(increase(http_server_requests_seconds_count{$(sel "$1")}[$2]))"; }
error_rate() {
  query "sum(increase(http_server_requests_seconds_count{$(sel "$1"),status=~\"5..\"}[$2])) or vector(0)" |
    # 5xx 和全部是兩次 increase()，各自外插，全錯的時候可能算出 1.003 這種略大於 1 的值，夾在 1
    awk -v total="$3" '{ if (total > 0) { r = $1 / total; printf "%.4f", (r > 1 ? 1 : r) } else print "" }'
}
p95() { query "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{$(sel "$1")}[$2])))"; }

# 把新版當 canary 觀察一段時間。過了回傳 0，沒過回傳 1（並印出原因）
judge_canary() {
  local canary="$1" baseline="$2" started elapsed window total rate p95_new p95_old
  started=$(date +%s)
  # 用 shell 導向丟掉輸出，不用 curl -o /dev/null：MSYS_NO_PATHCONV 下 Windows 的 curl.exe 會把 /dev/null 當成真的路徑
  if ! curl -fsS "$METRICS_URL/api/v1/query?query=1" > /dev/null; then
    echo "查不到指標（$METRICS_URL）：canary 沒有東西可以判斷，當作沒過。先起 ops/：docker compose -f ops/compose.yaml up -d" >&2
    return 1
  fi
  while :; do
    sleep "$CANARY_INTERVAL"
    elapsed=$(( $(date +%s) - started ))
    # 範圍至少 60 秒：Alloy 每 15 秒抓一次，increase() 要有好幾個點才算得準
    window="$(( elapsed > 60 ? elapsed : 60 ))s"
    total=$(requests "$canary" "$window"); total=${total:-0}
    rate=$(error_rate "$canary" "$window" "$total")
    log "canary 第 ${elapsed} 秒：$canary 接了 ${total%.*} 個請求，5xx 比例 ${rate:-（還沒有請求）}"
    if [ -n "$rate" ] && awk -v t="$total" -v min="$CANARY_MIN_REQUESTS" -v r="$rate" -v max="$CANARY_MAX_ERROR_RATE" \
        'BEGIN { exit !(t >= min && r > max) }'; then
      echo "沒過：5xx 比例 $rate 超過 $CANARY_MAX_ERROR_RATE" >&2
      return 1
    fi
    [ "$elapsed" -ge "$CANARY_SECONDS" ] && break
  done

  if awk -v t="$total" -v min="$CANARY_MIN_REQUESTS" 'BEGIN { exit !(t < min) }'; then
    echo "沒過：$CANARY_SECONDS 秒內新版只接到 ${total%.*} 個請求（至少要 $CANARY_MIN_REQUESTS），判斷不了" >&2
    return 1
  fi
  # p95 跟舊版「同一段時間、同一批流量」比，不設絕對門檻：AI 助理的延遲由模型決定，絕對值天天不同
  p95_new=$(p95 "$canary" "$window"); p95_old=$(p95 "$baseline" "$window")
  log "p95：新版 ${p95_new:-？} 秒、舊版 ${p95_old:-？} 秒（超過舊版的 $CANARY_MAX_P95_RATIO 倍、而且多 $CANARY_P95_SLACK 秒以上才算變慢）"
  if [ -n "$p95_new" ] && [ -n "$p95_old" ] && [ "$p95_new" != NaN ] && [ "$p95_old" != NaN ] &&
      awk -v n="$p95_new" -v o="$p95_old" -v k="$CANARY_MAX_P95_RATIO" -v d="$CANARY_P95_SLACK"         'BEGIN { exit !(n > o * k && n - o > d) }'; then
    echo "沒過：新版 p95 $p95_new 秒，超過舊版 $p95_old 秒的 $CANARY_MAX_P95_RATIO 倍、也多了 $CANARY_P95_SLACK 秒以上" >&2
    return 1
  fi
  return 0
}

# 等某個已存在的容器 healthy（rollback 用：docker start 沒有 --wait）
wait_healthy() {
  local id="$1" deadline=$(( $(date +%s) + WAIT_TIMEOUT )) status
  while :; do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$id")
    [ "$status" = healthy ] && return 0
    [ "$(date +%s)" -ge "$deadline" ] && { echo "等了 $WAIT_TIMEOUT 秒還不是 healthy（$status）" >&2; return 1; }
    sleep 2
  done
}

# 切到 target 之後關掉 old
switch_and_stop() {
  local target="$1" old="$2"
  route "$target"
  sleep "$DRAIN_SECONDS"
  log "關掉 $old（等手上的請求做完，最多 2 分 30 秒）"
  dc stop "$old"
}

# ── 主流程 ──────────────────────────────────────────────────────────────
if ! web_running; then
  # 第一次部署、web 還沒起來：沒有人在接流量，也沒有 nginx 可以切。直接起 app，跟以前的 apply.sh 一樣
  [ "$action" = deploy ] || { echo "web 沒在跑，沒有流量可以切" >&2; exit 1; }
  log "web 沒在跑：直接部署 app（還沒有流量要保護）"
  dc up -d --build --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" app
  exit 0
fi

active=$(active_slot)
idle=$(other "$active")

case "$action" in
  --status)
    dc exec -T web cat "$ROUTING_FILE"
    dc ps app app-green
    ;;

  --rollback)
    id=$(dc ps -aq "$idle")
    [ -n "$id" ] || { echo "$idle 沒有容器可以切回去（從來沒部署過，或被 down 掉了）。要回更舊的版本見 #19" >&2; exit 1; }
    log "切回 $idle（$(docker inspect -f '{{.Config.Image}} {{.Image}}' "$id" | cut -c1-40)），現在是 $active"
    # docker start 不是 compose up：up 會照現在的映像檔重建容器，那就不是「切回舊版」了。
    # 也不用 compose start：它會沿著 depends_on 去啟動模型（app 依賴 llm-chat），這裡只要這一個容器
    docker start "$id" > /dev/null
    wait_healthy "$id"
    switch_and_stop "$idle" "$active"
    log "完成：流量全部在 $idle"
    ;;

  deploy)
    log "現在接流量的是 $active，新版起在 $idle"
    # --build：映像檔有變才重 build；容器設定或映像檔變了才重建。--wait：等 healthcheck 通過
    dc up -d --build --no-deps --wait --wait-timeout "$WAIT_TIMEOUT" "$idle"

    if [ "$CANARY_PERCENT" -gt 0 ]; then
      route "$idle" "$CANARY_PERCENT" "$active"
      if ! judge_canary "$idle" "$active"; then
        route "$active"
        sleep "$DRAIN_SECONDS"
        # 只 stop 不刪：容器和 log 留著查原因（docker logs talkcal-app-green）
        dc stop "$idle"
        log "已切回 $active，新版 $idle 關掉了（log 還在）"
        exit 1
      fi
      log "canary 通過"
    fi

    switch_and_stop "$idle" "$active"
    log "完成：流量全部在 $idle；$active 留著舊版容器，cd/bluegreen.sh $env_name --rollback 可以切回去"
    ;;

  *)
    echo "不認得的動作：$action（只有 --rollback、--status）" >&2
    exit 1
    ;;
esac
