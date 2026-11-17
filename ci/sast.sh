#!/usr/bin/env bash
# 用 CodeQL 找自己寫的程式裡的安全問題（SAST）：SQL 拼字串、XSS、workflow 注入這類。
# 別人的套件已公布的漏洞由 ci/vulns.sh 掃。
#
#   ci/sast.sh            掃 HEAD
#   ci/sast.sh <commit>   掃指定的 commit
#
# 程式碼一樣用 git archive 複製進容器（理由見 ci/run.sh），報告複製回 ci/build/sast/。
# 不放進 checks.sh：CodeQL 映像檔約 2 GB、分析要幾分鐘，pre-push 每次都等不划算。
# 門檻（security-severity 7.0 以上就 exit 1）寫在 ci/sast/analyze.sh。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 別讓 Git Bash 改寫 /work（見 ci/run.sh）

commit=$(git rev-parse --verify "${1:-HEAD}^{commit}")
image=talkcal-sast
container=talkcal-sast-$$
out=ci/build/sast

if [ "${1:-HEAD}" = HEAD ] && [ -n "$(git status --porcelain)" ]; then
    echo "注意：有還沒 commit 的改動，它們不會被掃到（只掃 $(git rev-parse --short HEAD)）。" >&2
fi

if ! docker info >/dev/null 2>&1; then
    echo "連不到 Docker。先把 Docker Desktop 打開再跑。" >&2
    exit 1
fi

echo "── 準備 CodeQL 映像檔 ──"
# 第一次要下載約 700 MB；Dockerfile 沒變就整個用快取
git archive "$commit" ci/sast/Dockerfile \
    | ci/build-image.sh "$image" ci/sast/Dockerfile

trap 'docker rm --force "$container" >/dev/null 2>&1 || true' EXIT
# talkcal-sast-gradle：CodeQL 執行 ./gradlew 時下載的 Gradle 本體和 jar，本機跨次保留。
# 不跟 ci/run.sh 的 talkcal-ci-gradle 共用：那邊只掛 caches/、Gradle 本體在映像檔裡，路徑對不上
# SAST_GRADLE_HOME：給主機上的絕對路徑就改掛那個資料夾，用法同 run.sh 的 CI_GRADLE_CACHE（GitHub 上把這個資料夾存進 actions/cache）
docker create --name "$container" --volume "${SAST_GRADLE_HOME:-talkcal-sast-gradle}:/root/.gradle" \
    "$image" ci/sast/analyze.sh >/dev/null

echo "── 複製 $(git rev-parse --short "$commit") 的程式碼進容器、跑 CodeQL ──"
git archive "$commit" | docker cp - "$container:/work"

status=0
docker start --attach "$container" || status=$?

rm -rf "$out"
mkdir -p ci/build
docker cp "$container:/work/ci/build/sast" "$out" >/dev/null 2>&1 || true
exit $status
