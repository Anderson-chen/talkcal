#!/usr/bin/env bash
# 列出 EC2 上會跑的第三方映像檔，一行一個。給 ci/vulns.sh 當參數（.github/workflows/ci.yml 的 vulns、third-party-images）。
#
#   ci/vulns.sh $(ci/compose-images.sh)
#
# 從 compose 讀，不在 ci.yml 另外抄一份：升版、加服務只改 compose，掃的清單自動跟上（#235）。
# 交給 compose 自己解析（config --images），不 grep：疊上去的檔案會蓋掉 image、有 profiles 的服務不會起（mailpit），
# 這些 grep 都看不出來。--no-interpolate：不代換 ${}，所以不需要 deploy/.env。
#
# 疊的檔案跟 infra/envs/1-ec2/deploy.sh 一樣（那邊改了這裡要跟著改）；model_backend 用預設的 bedrock。
# talkcal/ 開頭的是自己 build 的（app、web 由 ci.yml build 好再掃），略過。不掃哪些、為什麼，見 ci/README.md「漏洞掃描」

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

{
    docker compose -f deploy/compose.yaml -f infra/envs/1-ec2/compose.ec2.yaml -f infra/envs/1-ec2/compose.bedrock.yaml \
        config --no-interpolate --images
    docker compose -f ops/compose.yaml -f infra/envs/1-ec2/compose.ops.yaml config --no-interpolate --images
} | grep -v '^talkcal/' | sort -u
