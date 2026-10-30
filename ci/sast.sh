#!/usr/bin/env bash
# 原始碼的安全靜態分析（SAST）：用 CodeQL 找自己寫的程式裡的安全問題 —— SQL 拼字串、XSS、workflow 注入…（#235）。
# 跟 ci/vulns.sh 分工：那邊找「別人的套件」已公布的漏洞，這裡找「自己的程式碼」的寫法。
#
#   ci/sast.sh            掃 HEAD
#   ci/sast.sh <commit>   掃指定的 commit
#
# 跟 ci/run.sh 一樣：用 git archive 只把那個 commit 複製進容器，沒 commit 的檔案、node_modules、build/ 都不會被掃到；
# 跑完容器丟掉，只把報告拿回主機的 ci/build/sast/。
# 不放進 checks.sh：CodeQL 映像檔約 2 GB、分析要幾分鐘，pre-push 每次都等不划算。GitHub 上是自己一個 job（sast）。
#
# security-severity 7.0 以上（HIGH、CRITICAL）就 exit 1，CI 紅燈 —— 門檻跟 vulns.sh 同一條線，理由見 ci/README.md。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 同 run.sh：別讓 Git Bash 改寫 /work

commit=$(git rev-parse --verify "${1:-HEAD}^{commit}")
image=talkcal-sast
container=talkcal-sast-$$
out=ci/build/sast

if [ "${1:-HEAD}" = HEAD ] && [ -n "$(git status --porcelain)" ]; then
    echo "注意：有還沒 commit 的改動，它們不會被掃到（只掃 $(git rev-parse --short HEAD)）。" >&2
fi

echo "── 準備 CodeQL 映像檔 ──"
# 同 run.sh：build context 從那個 commit 取。Dockerfile 沒變時整個映像檔用快取；第一次要下載約 700 MB
git archive "$commit" ci/sast/Dockerfile \
    | docker build --quiet --tag "$image" --file ci/sast/Dockerfile - >/dev/null

trap 'docker rm --force "$container" >/dev/null 2>&1 || true' EXIT
# talkcal-sast-gradle：CodeQL 跑 ./gradlew 拿依賴時下載的 Gradle 本體和 jar，本機跨次保留（同 run.sh 的 talkcal-ci-gradle，
# 但不共用：那邊的 Gradle 本體烤在映像檔裡、volume 只掛 caches/，路徑對不上）
docker create --name "$container" --volume talkcal-sast-gradle:/root/.gradle "$image" ci/sast/analyze.sh >/dev/null

echo "── 複製 $(git rev-parse --short "$commit") 的程式碼進容器、跑 CodeQL ──"
git archive "$commit" | docker cp - "$container:/work"

status=0
docker start --attach "$container" || status=$?

rm -rf "$out"
mkdir -p ci/build
docker cp "$container:/work/ci/build/sast" "$out" >/dev/null 2>&1 || true
exit $status
