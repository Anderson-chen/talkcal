#!/usr/bin/env bash
# 告警端到端測試：真的造出問題，確認告警信寄得出來。驗的是「指標 → Mimir → Grafana 告警 → SMTP」整條鏈。
#
#   ci/alerting.sh            # 約 15 分鐘
#   KEEP=1 ci/alerting.sh     # 跑完不收容器，留著到 http://localhost:13000、http://localhost:18025 看
#
# 起 ops/compose.yaml 的整組觀測（疊 ci/alerting/compose.yaml，跟正在跑的那組分開）、Mailpit（假信箱）、
# 一支假 app 和一支假 postgres_exporter（ci/alerting/fake-app.py，只吐指標），然後依序：
#   1. 正常流量 3 分鐘：一封都不能有          → 擋「規則太敏感、正常也寄信」
#   2. 假 app 全部回 5xx：要收到 SLO 快燒      → 擋 burn rate 的錄製規則、門檻、SMTP 任何一段斷掉
#   3. 停掉假 app：要收到 app 掛了             → 擋 rules.yaml 的門檻、for、noDataState 寫錯
#   4. 假 app 起來：要收到 app 掛了的 RESOLVED  → 擋恢復通知被關掉
# 每一步輪詢 Mailpit 的 API，時限比實測多抓一倍：寧可慢一點綠，不要因為時間差誤判。
#
# 刻意不驗：慢燒（視窗要好幾小時，算式交給 ci/rules.sh 的單元測試）、真的信箱收不收得到（CI 裡沒有）。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 同 run.sh：別讓 Git Bash 把容器裡的 /tmp 改寫成 Windows 路徑

project=talkcal-alerttest
mimir=http://127.0.0.1:19009
grafana=http://127.0.0.1:13000
mailpit=http://127.0.0.1:18025
mkdir -p ci/build
summary=ci/build/alerting.md

# ops/compose.yaml 平常從 deploy/.env 拿寄信的設定；這裡不讀它，只給寄信要的兩個值（shell 的環境變數優先於 .env 檔）。
# .test 是保留網域，信只會進 Mailpit。其他 ALERT_SMTP_* 明寫成空的：
# 不然呼叫的 shell 裡剛好有（例如測過 Gmail 的帳密）就會被帶進來，對 Mailpit 做 AUTH 失敗，也等於把真密碼交給測試容器
export ALERT_SMTP_HOST=mailpit:1025
export ALERT_EMAIL_TO=oncall@example.test
export ALERT_SMTP_USER='' ALERT_SMTP_PASSWORD='' ALERT_SMTP_FROM=''

dc() {
  docker compose -p "$project" --profile mailtest -f ops/compose.yaml -f ci/alerting/compose.yaml "$@"
}
# 容器、volume、網路一起收掉
teardown() { dc down -v --remove-orphans >/dev/null 2>&1 || true; }

start=$(date +%s)
log() {
  local line
  line="$(printf '%4ds  %s' $(( $(date +%s) - start )) "$*")"
  echo "$line"
  echo "| $line |" >> "$summary"
}

cleanup() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "::group::失敗時的狀態"
    echo "--- Mailpit 收到的信"; subjects || true
    echo "--- Grafana 告警狀態"; curl -fsS "$grafana/api/prometheus/grafana/api/v1/rules" || true; echo
    echo "--- Grafana log"; dc logs --tail 80 grafana || true
    echo "::endgroup::"
    log "失敗"
  fi
  if [ "${KEEP:-}" = 1 ]; then
    echo "KEEP=1：容器留著，收的時候跑 docker compose -p $project down -v"
  else
    teardown
  fi
  exit "$status"
}
trap cleanup EXIT

# Mailpit 收到的每封信的主旨，一行一個。不用 jq：Git Bash 沒有。
# 一封都沒有時 grep 回 1，pipefail 會把它當失敗；沒信是正常結果，所以吞掉（curl 失敗照樣報錯）
subjects() {
  local json
  json=$(curl -fsS "$mailpit/api/v1/messages?limit=200")
  { grep -o '"Subject":"[^"]*"' <<<"$json" || true; } | cut -d'"' -f4
}

# wait_until <說明> <秒數> <指令…>：每 10 秒試一次，時限內成功就回來，否則整支失敗
wait_until() {
  local what=$1 timeout=$2; shift 2
  local deadline=$(( $(date +%s) + timeout ))
  until "$@" >/dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      log "❌ ${timeout} 秒內沒等到：$what"
      return 1
    fi
    sleep 10
  done
  log "✅ $what"
}

has_mail() { subjects | grep -qE "$1"; }
mimir_has_up() { curl -fsS "$mimir/prometheus/api/v1/query?query=up%7Bjob%3D%22talkcal-app%22%7D%3D%3D1" | grep -q '"value"'; }
rules_loaded() {
  local rules
  rules=$(curl -fsS "$grafana/api/v1/provisioning/alert-rules")
  grep -q '"uid":"talkcal-app-down"' <<<"$rules" && grep -q '"uid":"slo-fast-burn"' <<<"$rules"
}
mailpit_up() { curl -fsS "$mailpit/api/v1/messages" >/dev/null; }

printf '| 經過時間、事件 |\n|---|\n' > "$summary"

# 上一次沒收乾淨（例如 KEEP=1）就從頭來：全新的 Mimir 才不會被舊流量稀釋
teardown
# 映像檔一次平行拉好，不必等 Mimir 收到 up 之後才開始拉 grafana
dc pull --quiet

# 先讓指標流起來，再起 Grafana：一起起的話，Grafana 第一次評估時 Mimir 可能還沒有 up，
# app-down（noDataState: Alerting）會照規則當成「抓不到」。真的環境也是 app 先起、觀測後到
log "起 mimir、alloy、假 app、假 postgres_exporter"
dc up -d mimir alloy fake-app fake-postgres-exporter
wait_until "Mimir 收到假 app 的 up = 1" 180 mimir_has_up

log "起 grafana、mailpit"
dc up -d grafana mailpit
wait_until "Grafana 載入告警規則（talkcal-app-down、slo-fast-burn）" 180 rules_loaded
wait_until "Mailpit 可以查信" 60 mailpit_up

log "1. 正常流量 3 分鐘"
sleep 180
mails=$(subjects)
if [ -n "$mails" ]; then
  log "❌ 正常流量卻收到信："
  echo "$mails"
  exit 1
fi
log "✅ 正常流量 3 分鐘：0 封"

log "2. 假 app 全部回 5xx"
dc exec -T fake-app sh -c 'echo 5xx > /tmp/mode'
# 實測約 4 分鐘：rate5m 要兩個點、Mimir 每分鐘錄一次、Grafana 每分鐘評估一次、policies.yaml 的 group_wait 30 秒
wait_until "收到「SLO 快燒」" 600 has_mail '^\[FIRING:[0-9]+\] SLO 快燒'

log "3. 停掉假 app"
dc exec -T fake-app sh -c 'echo ok > /tmp/mode'
dc stop fake-app
# 實測約 2 分鐘：for 1m + group_wait 30 秒
wait_until "收到「app 掛了」" 300 has_mail '^\[FIRING:[0-9]+\] talkcal app 掛了'

log "4. 假 app 起來"
dc start fake-app
# 同一組告警已經寄過，恢復通知要等 policies.yaml 的 group_interval（5 分鐘，從上一封算起）
wait_until "收到「app 掛了」的 RESOLVED" 480 has_mail '^\[RESOLVED\] talkcal app 掛了'

# 寄給了設定的收件人（contact-points.yaml 的 $ALERT_EMAIL_TO 有換成 env 的值）
curl -fsS "$mailpit/api/v1/messages?limit=200" | grep -q '"Address":"oncall@example.test"' \
  || { log "❌ 收件人不是 oncall@example.test"; exit 1; }

log "全部通過，收到的信："
subjects | while read -r s; do log "　$s"; done
