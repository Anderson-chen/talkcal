#!/usr/bin/env bash
# 部署的冒煙測試（#225）：確認「這個環境」真的能用。CI 測得到程式和設定的邏輯，測不到環境的差異
# （SSM 拉下來的 .env、網路、憑證、真的 SMTP），這些只有部署當下在機器上才驗得到。
# deploy.sh 呼叫兩次，任何一項沒過就結束碼 1，deploy.sh 跟著失敗：
#
#   MODEL_BACKEND=bedrock bash smoke.sh env deploy/.env   # 啟動 app 之前：.env 的換行、必要的 key
#   bash smoke.sh services deploy/.env                    # 全部起來之後：網站、觀測、告警寄信
#
# 只印 key 的名字和檢查結果，不印任何值。
# 每次部署都寄一封測試信（主旨是 DeploySmokeTest）：部署是手動、偶爾才做，多一封信的代價小，
# 換來每次都證明 SMTP 帳密還能用。同一天要連續部署好幾次時，用 SMOKE_SEND_EMAIL=0 關掉。
set -euo pipefail

mode="${1:?用法：smoke.sh env|services <.env 路徑>}"
dotenv="${2:?用法：smoke.sh env|services <.env 路徑>}"
# 機器上 Grafana、Mimir 只綁 127.0.0.1（ops/compose.yaml）；本機驗這支腳本時可以指到別的地方
GRAFANA_URL="${GRAFANA_URL:-http://127.0.0.1:3000}"
MIMIR_URL="${MIMIR_URL:-http://127.0.0.1:9009}"

failures=()
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1" >&2; failures+=("$1"); }

finish() {
  if [ "${#failures[@]}" -gt 0 ]; then
    echo "冒煙測試（$mode）沒過 ${#failures[@]} 項：" >&2
    printf '  - %s\n' "${failures[@]}" >&2
    exit 1
  fi
  echo "冒煙測試（$mode）全部通過"
}

# 取 .env 的值，不 source：source 會把值當 shell 執行。去掉頭尾成對的引號（secrets.tf 用單引號包 SMTP 密碼）
env_get() {
  local v
  v="$(grep -m1 "^$1=" "$dotenv" | cut -d= -f2- || true)"
  if [[ "$v" =~ ^\'(.*)\'$ || "$v" =~ ^\"(.*)\"$ ]]; then v="${BASH_REMATCH[1]}"; fi
  printf '%s' "$v"
}

# 剛 up 完的服務要一點時間：Grafana 啟動、Alloy 抓第一輪（15 秒一次）再推進 Mimir。
# 每 5 秒試一次，到時間還不行才算失敗
retry() {
  local seconds="$1"; shift
  local deadline=$((SECONDS + seconds))
  until "$@"; do
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep 5
  done
}

check_env() {
  : "${MODEL_BACKEND:?缺 MODEL_BACKEND}"
  # 寫出空檔案卻回 0 的事真的發生過（snap 版的 aws，#74）
  if [ ! -s "$dotenv" ]; then
    fail ".env 是空的：檢查 aws --version（不要用 snap 版）、SSM 參數"
    finish
  fi

  # 2026-10-29：從 Windows apply，CRLF 的 secrets.tf 讓 SSM 的 .env 每行多一個 \r，
  # deploy.sh 取出的 DB 密碼多一個字元、ALTER ROLE 設錯，app 要等 --wait 判 unhealthy 才失敗，網站斷了 5 分鐘
  local cr_lines
  cr_lines="$(awk '/\r$/ { n++ } END { print n + 0 }' "$dotenv")"
  if [ "$cr_lines" -gt 0 ]; then
    fail ".env 的換行：有 $cr_lines 行以 \\r 結尾（CRLF）。多半是從 Windows apply 時 secrets.tf 是 CRLF，見 README 的疑難排解"
  else
    pass ".env 的換行是 LF"
  fi

  local required=(SITE_ADDRESS DB_PASSWORD)
  case "$MODEL_BACKEND" in
    bedrock) required+=(BEDROCK_BASE_URL BEDROCK_MODEL BEDROCK_API_KEY) ;;
    gpu)     required+=(MODELS_DIR) ;;
  esac
  # 有設 SMTP 才要收件人：沒收件人的話 Grafana 會寄到 compose 給的 .invalid 位址，等於沒寄
  [ -z "$(env_get ALERT_SMTP_HOST)" ] || required+=(ALERT_EMAIL_TO)

  local key missing=()
  for key in "${required[@]}"; do
    [ -n "$(env_get "$key")" ] || missing+=("$key")
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    fail ".env 缺值：${missing[*]}"
  else
    pass ".env 必要的 key 都有值（${required[*]}）"
  fi
  finish
}

# 走 Caddy 的 https，跟使用者同一條路：DNS、憑證、Caddy → nginx → app 都要通
http_code() { curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$1" 2>/dev/null || true; }
https_ok() { [ "$(http_code "$1")" = 200 ]; }
check_https() {
  if retry 60 https_ok "$2"; then pass "$1 200（$2）"; else fail "$1：$2 回 $(http_code "$2")，要 200"; fi
}

# Grafana 用資料源設定去連一次，連得上才算：位址、網路、對方 ready 都包含在內。
# Mimir（prometheus）、Loki 有 health API；Tempo 的 plugin 在 Grafana 11.4 沒實作（回 404 Method not implemented），
# 改經 Grafana 的資料源 proxy 打 Tempo 的 /ready，一樣是走 Grafana 那份設定連過去
datasource_path() {
  case "$1" in
    tempo) echo "/api/datasources/proxy/uid/tempo/ready" ;;
    *)     echo "/api/datasources/uid/$1/health" ;;
  esac
}
datasource_ok() {
  curl -fsS --max-time 10 "$GRAFANA_URL$(datasource_path "$1")" >/dev/null 2>&1
}

app_scraped() {
  curl -fsS --max-time 10 "$MIMIR_URL/prometheus/api/v1/query" --data-urlencode 'query=up{job="talkcal-app"}' 2>/dev/null \
    | python3 -c 'import json,sys; r=json.load(sys.stdin)["data"]["result"]; sys.exit(0 if r and all(x["value"][1]=="1" for x in r) else 1)' 2>/dev/null
}

# 用 Grafana 的 receivers test API 寄一封。拿 Grafana 現在用的 email contact point 去寄（不是從 .env 另外組），
# 驗到的就是告警真的會走的那一份設定。回傳每個 receiver 的 status；失敗時 error 是 SMTP 的回應（例如 535 帳密錯），不含密碼
check_email() {
  if [ -z "$(env_get ALERT_SMTP_HOST)" ]; then
    pass "告警寄信：跳過（沒設 ALERT_SMTP_HOST，不寄信）"
    return
  fi
  if [ "${SMOKE_SEND_EMAIL:-1}" = 0 ]; then
    pass "告警寄信：跳過（SMOKE_SEND_EMAIL=0）"
    return
  fi
  local body result
  body="$(curl -fsS --max-time 10 "$GRAFANA_URL/api/alertmanager/grafana/config/api/v1/alerts" | python3 -c '
import json, sys
receivers = [r for r in json.load(sys.stdin)["alertmanager_config"]["receivers"] if r["name"] == "email"]
print(json.dumps({
    "receivers": receivers,
    "alert": {
        "labels": {"alertname": "DeploySmokeTest"},
        "annotations": {"summary": "部署的冒煙測試寄的，確認告警信寄得出去，不用處理（" + sys.argv[1] + "）"},
    },
}))' "${APP_VERSION:-unknown}")" || { fail "告警寄信：讀不到 Grafana 的 email contact point"; return; }
  result="$(curl -sS --max-time 60 -X POST -H 'Content-Type: application/json' -d "$body" \
    "$GRAFANA_URL/api/alertmanager/grafana/config/api/v1/receivers/test" | python3 -c '
import json, sys
resp = json.load(sys.stdin)
configs = [c for r in resp.get("receivers") or [] for c in r["grafana_managed_receiver_configs"]]
if not configs:
    print("Grafana 沒有回傳任何 receiver：%s" % resp.get("message", ""))
    sys.exit(1)
bad = [c for c in configs if c.get("status") != "ok"]
print("; ".join("%s %s：%s" % (c.get("name"), c.get("status"), c.get("error", "")) for c in bad))
sys.exit(1 if bad else 0)')" \
    && pass "告警寄信：Grafana 寄出測試信，status ok" \
    || fail "告警寄信：${result:-Grafana 沒回應}"
}

check_services() {
  local site
  site="$(env_get SITE_ADDRESS)"
  check_https "網站首頁" "https://$site/"
  # 沒登入也能打的 API：證明 nginx 轉得到 app（只看首頁的話，app 掛了首頁照樣 200）
  check_https "/api 健康檢查" "https://$site/api/auth/providers"

  local uid
  for uid in mimir loki tempo; do
    if retry 90 datasource_ok "$uid"; then pass "Grafana 資料源 $uid 連得上"; else fail "Grafana 資料源 $uid 連不上（$GRAFANA_URL$(datasource_path "$uid")）"; fi
  done

  if retry 90 app_scraped; then
    pass 'Alloy 抓得到 app：Mimir 裡 up{job="talkcal-app"} == 1'
  else
    fail 'Alloy 抓不到 app：Mimir 裡沒有 up{job="talkcal-app"} == 1（看 Alloy 的除錯頁 127.0.0.1:12345）'
  fi

  check_email
  finish
}

case "$mode" in
  env)      check_env ;;
  services) check_services ;;
  *)        echo "用法：smoke.sh env|services <.env 路徑>" >&2; exit 2 ;;
esac
