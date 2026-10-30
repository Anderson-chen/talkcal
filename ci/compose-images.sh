#!/usr/bin/env bash
# 列出會跟著部署上線的第三方映像檔，一行一個。給 ci/vulns.sh 當參數（.github/workflows/ci.yml 的 vulns job）：
#
#   ci/vulns.sh talkcal/app:local talkcal/web:local $(ci/compose-images.sh)
#
# 從 compose 檔讀，不在 ci.yml 另外抄一份：升版、加服務只改 compose，掃的清單自動跟上（#235）。
#
# 讀哪幾份：infra/envs/1-ec2/deploy.sh 在 EC2 上 up 的那些 —— deploy/compose.yaml＋infra/envs/1-ec2/compose.*.yaml、
# ops/compose.yaml（觀測堆疊）。不讀的：
#   - deploy/compose.chaos.yaml（toxiproxy）：只在本機故障演練時疊上去
#   - ci/alerting/compose.yaml（python）：只在 CI 的告警測試裡跑，已經釘 digest
#   - docs/drills/：演練紀錄
# talkcal/ 開頭的是自己 build 的（app、web 由 ci.yml build 好再掃；llama-server 見 ci/README.md），
# ${} 是 compose 執行時才代換的，這裡都略過。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

grep -hE '^[[:space:]]*image:' deploy/compose.yaml infra/envs/1-ec2/compose.*.yaml ops/compose.yaml \
    | awk '{ print $2 }' \
    | grep -v -e '^talkcal/' -e '\$' \
    | sort -u
