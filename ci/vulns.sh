#!/usr/bin/env bash
# 掃映像檔裡有沒有已知漏洞（CVE）。用 Trivy，在容器裡跑。
#
#   ci/vulns.sh talkcal/app:local talkcal/web:local postgres:18.6-alpine
#
# 掃的是 build 好的映像檔，不是 build.gradle、package.json：映像檔才是真的會上線的東西，
# 一次看得到兩種 —— 基底映像檔的作業系統套件（openssl、curl…），和 jar 裡打包的 Java 函式庫（Tomcat、Jackson…）。
#
# 有漏洞就 exit 1，CI 紅燈、合併不了。
# 評估過決定接受的（例如修不了、也碰不到的），寫進 ci/trivyignore.yaml，要附理由和到期日。
#
# 只看 HIGH、CRITICAL，而且只看已經有修補版本的（--ignore-unfixed）：
# 上游還沒修的我們也沒得升，擋下來只會讓每個 PR 都紅、卻沒有任何事可做。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 同 run.sh：別讓 Git Bash 改寫 /var/run/docker.sock

# 版本和 digest 都釘死。Trivy 在 2026-03 被入侵過（v0.69.4 和 trivy-action 的 tag 被換成會偷 secret 的版本），
# 所以不用 trivy-action、不用會移動的 tag，用的是事件之後發的版本。
# 版本寫在 ci/Dockerfile（CI 映像檔掃授權用同一版），這裡讀過來，不再寫一次 —— 跟 ci.yml 從 compose 讀 postgres 版本同一個做法
image=$(grep -oE 'aquasec/trivy:[^ ]+' ci/Dockerfile)

# Windows 上 docker 要 C:/... 形式的路徑（pwd -W），Linux、macOS 沒有 -W 就用 pwd
repo=$(pwd -W 2>/dev/null || pwd)

status=0
out=ci/build/vulns.md
mkdir -p ci/build
echo "## 漏洞掃描（HIGH、CRITICAL，有修補版本的）" > "$out"

for target in "$@"; do
    echo "── $target ──"
    # docker.sock：讓 Trivy 直接讀本機 Docker 裡的映像檔，不必先推到 registry
    # talkcal-trivy-cache：漏洞資料庫（幾十 MB），本機跨次保留；GitHub 每次都是新機器，會重新下載
    # 一個映像檔有漏洞不提早結束：每個都掃完，一次看到全部（跟 checks.sh 同一個理由）
    code=0
    report=$(docker run --rm \
        --volume /var/run/docker.sock:/var/run/docker.sock \
        --volume talkcal-trivy-cache:/root/.cache/trivy \
        --volume "$repo/ci/trivyignore.yaml:/trivyignore.yaml:ro" \
        "$image" image --quiet --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed \
        --ignorefile /trivyignore.yaml --exit-code 1 \
        --format table "$target") || code=$?
    echo "$report"
    { echo; echo "### $target"; echo; echo '```'; echo "$report"; echo '```'; } >> "$out"
    # 1 是「有漏洞」；其他非 0 是 Trivy 自己出錯（例如映像檔不存在）—— 兩種都算失敗，不能當成「沒漏洞」
    [ "$code" -ne 0 ] && status=1
done

exit $status
