#!/usr/bin/env bash
# 在自己的電腦上跑（infra/envs/1-ec2 底下，要能 terraform output）：開機、關機。
#
#   ./power.sh stop    # 先把 Healthchecks 的 check 暫停，再關機
#   ./power.sh start   # 開機，等到 check 恢復監控
#
# 暫停要 export HEALTHCHECKS_API_KEY=...（可寫的 key）。為什麼要暫停、key 從哪來：見 README.md 的「省錢」。
set -euo pipefail

cd "$(dirname "$0")"
# 預設值跟 variables.tf 的 region、aws_profile 一樣
REGION="${AWS_REGION:-ap-northeast-1}"
PROFILE="${AWS_PROFILE:-DEV}"
# 自架 Healthchecks（本機驗證）時換成它的網址
HEALTHCHECKS_API_URL="${HEALTHCHECKS_API_URL:-https://healthchecks.io}"
HEARTBEAT_UUID=""

# 有設 heartbeat_url 和 key 才管 Healthchecks；沒設就只開關機。
# check 的 UUID 是 ping 網址的最後一段（https://hc-ping.com/<uuid>）；slug 形式的網址取不到，要用 UUID 那種
heartbeat_enabled() {
  local ping_url
  ping_url="$(terraform output -raw heartbeat_url 2>/dev/null || true)"
  HEARTBEAT_UUID="${ping_url##*/}"
  [ -n "$ping_url" ] && [ -n "${HEALTHCHECKS_API_KEY:-}" ]
}

# hc_api <GET|POST> [路徑後綴]：呼叫這個 check 的 Healthchecks API。
# key 從 stdin 交給 curl（-H @-），不放在命令列：同一台電腦的其他程式用 ps 看得到命令列。
# --max-time：Healthchecks 沒回應時不要讓開關機卡住
hc_api() {
  printf 'X-Api-Key: %s\n' "$HEALTHCHECKS_API_KEY" |
    curl -fsS --max-time 15 -H @- -X "$1" "$HEALTHCHECKS_API_URL/api/v3/checks/$HEARTBEAT_UUID${2:-}"
}

pause_heartbeat() {
  # 暫停失敗也照樣關機：關機是為了省錢，不該被監控卡住；只是會收到一封信
  if hc_api POST /pause >/dev/null; then
    echo "Healthchecks：已暫停，開機後第一個 heartbeat 會自己恢復"
  else
    echo "Healthchecks 暫停失敗（key 是 read-only？網址是 slug 形式？）：照樣關機，約 6 分鐘後會收到一封 DOWN 的信" >&2
  fi
}

# 開機後等 check 回到 up。暫停中的 check 要收到 heartbeat 才會恢復：Grafana 起不來的話它會一直停在 paused、
# 不會寄信 —— 正好漏掉這套要抓的「Grafana 掛了」。所以開機這一步自己盯到恢復為止
wait_heartbeat() {
  local status=""
  # 15 秒一次、最多 10 分鐘：開機、容器起來、Grafana 送出第一個 heartbeat 通常 2～3 分鐘
  for _ in $(seq 1 "${HEARTBEAT_WAIT_TRIES:-40}"); do
    status="$(hc_api GET | sed -n 's/.*"status": *"\([a-z]*\)".*/\1/p')" || true
    if [ "$status" = up ]; then
      echo "Healthchecks：已恢復監控"
      return 0
    fi
    sleep "${HEARTBEAT_WAIT_SECONDS:-15}"
  done
  echo "開機後一直沒收到 heartbeat（check 現在是 ${status:-查不到}）：Grafana 可能沒起來，" \
       "而且暫停中的 check 不會替你寄信。用 terraform output -raw grafana_tunnel 連進去看" >&2
  return 1
}

case "${1:-}" in
  stop|start) ;;
  *) echo "用法：$0 stop|start" >&2; exit 1 ;;
esac

# 先拿 instance id，拿不到（還沒 terraform init、state 讀不到）就停在這裡。
# 不能寫成 aws ... --instance-ids "$(terraform output ...)"：命令替換在參數裡失敗時 set -e 不會停，
# 會帶著空字串去呼叫 aws（實測過，AWS 回 InvalidInstanceID.Malformed）
id="$(terraform output -raw instance_id)"

if [ "$1" = stop ]; then
  if heartbeat_enabled; then
    pause_heartbeat
  else
    echo "沒設 heartbeat_url 或 HEALTHCHECKS_API_KEY：不暫停，有設 heartbeat 的話約 6 分鐘後會收到一封 DOWN 的信" >&2
  fi
  aws ec2 stop-instances --instance-ids "$id" --region "$REGION" --profile "$PROFILE" >/dev/null
  echo "關機中：$id"
else
  aws ec2 start-instances --instance-ids "$id" --region "$REGION" --profile "$PROFILE" >/dev/null
  echo "開機中：$id。容器會自己起來"
  if heartbeat_enabled; then
    wait_heartbeat
  else
    echo "沒設 heartbeat_url 或 HEALTHCHECKS_API_KEY：不確認 heartbeat 有沒有恢復" >&2
  fi
fi
