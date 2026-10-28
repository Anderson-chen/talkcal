#!/usr/bin/env bash
# 在自己的電腦上跑（infra/envs/1-ec2 底下，要能 terraform output）：開機、關機。
#
#   ./power.sh stop    # 先把 Healthchecks 的 check 暫停，再關機
#   ./power.sh start   # 開機
#
# 為什麼不直接 aws ec2 stop-instances：機器平常關機，關了之後 Grafana 的 heartbeat 也停了（#40），
# 不先暫停的話 Healthchecks 6 分鐘後會寄一封「掛了」的信，關一次響一次，久了就沒人理它的信。
# 開機不用另外恢復：暫停中的 check 收到下一個 heartbeat 就自己回到監控（本機實測過）。
#
# 暫停要 Healthchecks 的 API key（Project Settings → API Access，選可以寫的那種，不是 read-only）。
# 只有這支腳本用得到，機器上不需要，所以不進 SSM、不進 git：用環境變數給
#   export HEALTHCHECKS_API_KEY=...
# 沒給（或沒設 heartbeat_url）就跳過暫停，照樣關機，並提醒會收到一封信。
set -euo pipefail

cd "$(dirname "$0")"
# 預設值跟 variables.tf 的 region、aws_profile 一樣
REGION="${AWS_REGION:-ap-northeast-1}"
PROFILE="${AWS_PROFILE:-DEV}"
# 自架 Healthchecks（本機驗證）時換成它的網址
HEALTHCHECKS_API_URL="${HEALTHCHECKS_API_URL:-https://healthchecks.io}"

pause_heartbeat() {
  local ping_url uuid
  ping_url="$(terraform output -raw heartbeat_url 2>/dev/null || true)"
  if [ -z "$ping_url" ] || [ -z "${HEALTHCHECKS_API_KEY:-}" ]; then
    echo "沒設 heartbeat_url 或 HEALTHCHECKS_API_KEY：不暫停，有設 heartbeat 的話約 6 分鐘後會收到一封 DOWN 的信" >&2
    return 0
  fi
  # Ping URL 的最後一段就是 check 的 UUID（https://hc-ping.com/<uuid>）。用 slug 形式的網址時這裡會不對，所以要用 UUID 那種
  uuid="${ping_url##*/}"
  # 暫停失敗也照樣關機：關機是為了省錢，不該被監控卡住；只是會收到一封信
  if curl -fsS -X POST -H "X-Api-Key: $HEALTHCHECKS_API_KEY" \
      "$HEALTHCHECKS_API_URL/api/v3/checks/$uuid/pause" >/dev/null; then
    echo "Healthchecks：已暫停，開機後第一個 heartbeat 會自己恢復"
  else
    echo "Healthchecks 暫停失敗（key 是 read-only？網址是 slug 形式？）：照樣關機，約 6 分鐘後會收到一封 DOWN 的信" >&2
  fi
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
  pause_heartbeat
  aws ec2 stop-instances --instance-ids "$id" --region "$REGION" --profile "$PROFILE" >/dev/null
  echo "關機中：$id"
else
  aws ec2 start-instances --instance-ids "$id" --region "$REGION" --profile "$PROFILE" >/dev/null
  echo "開機中：$id。容器會自己起來；Grafana 起來後的第一個 heartbeat 讓 Healthchecks 恢復監控"
fi
