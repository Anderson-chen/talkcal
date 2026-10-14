#!/usr/bin/env bash
# 掃映像檔裡有沒有已知漏洞（CVE）。用 Trivy，在容器裡跑。
#
#   ci/vulns.sh talkcal/app:local talkcal/web:local postgres:18.6-alpine
#
# 掃的是 build 好的映像檔，不是 build.gradle、package.json：映像檔才是真的會上線的東西，
# 一次看得到兩種 —— 基底映像檔的作業系統套件（openssl、curl…），和 jar 裡打包的 Java 函式庫（Tomcat、Jackson…）。
#
# 目前只回報、不擋（永遠 exit 0）：第一次掃就有幾十個現成的漏洞，直接擋的話每個 PR 都會紅。
# 先讓結果看得到，修完現有的再改成擋（#15）。
#
# 只列 HIGH、CRITICAL，而且只列已經有修補版本的（--ignore-unfixed）：
# 上游還沒修的我們也沒得升，列出來只是噪音。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 同 run.sh：別讓 Git Bash 改寫 /var/run/docker.sock

# 版本和 digest 都釘死。Trivy 在 2026-03 被入侵過（v0.69.4 和 trivy-action 的 tag 被換成會偷 secret 的版本），
# 所以不用 trivy-action、不用會移動的 tag，用的是事件之後發的版本
image=aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969

out=ci/build/vulns.md
mkdir -p ci/build
echo "## 漏洞掃描（HIGH、CRITICAL，有修補版本的）" > "$out"

for target in "$@"; do
    echo "── $target ──"
    # docker.sock：讓 Trivy 直接讀本機 Docker 裡的映像檔，不必先推到 registry
    # talkcal-trivy-cache：漏洞資料庫（幾十 MB），本機跨次保留；GitHub 每次都是新機器，會重新下載
    report=$(docker run --rm \
        --volume /var/run/docker.sock:/var/run/docker.sock \
        --volume talkcal-trivy-cache:/root/.cache/trivy \
        "$image" image --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed \
        --format table "$target")
    echo "$report"
    { echo; echo "### $target"; echo; echo '```'; echo "$report"; echo '```'; } >> "$out"
done
