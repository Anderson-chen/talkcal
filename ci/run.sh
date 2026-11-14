#!/usr/bin/env bash
# 把一個 commit 放進 ci/Dockerfile 的容器執行 ci/checks.sh，執行完刪掉容器，摘要和報告複製回 ci/build/。
# 本機、pre-push hook、GitHub Actions 都執行這一支，理由見 docs/adr/0008-ci-same-script-in-container.md。
#
#   ci/run.sh            測 HEAD
#   ci/run.sh <commit>   測指定的 commit
#
# 程式碼用 git archive 複製進容器，不掛資料夾：沒 commit 的檔案、殘留的 build/、node_modules 才不會混進來，
# 而且 Windows 掛資料夾進容器只有約 20 MB/s。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Git Bash 會把看起來像路徑的參數（/var/run/docker.sock、/work）改寫成 C:/Program Files/Git/...，傳給 docker 就錯了。
# Linux、macOS 上這個變數沒有作用
export MSYS_NO_PATHCONV=1

commit=$(git rev-parse --verify "${1:-HEAD}^{commit}")
image=talkcal-ci
container=talkcal-ci-$$
out=ci/build

if [ "${1:-HEAD}" = HEAD ] && [ -n "$(git status --porcelain)" ]; then
    echo "注意：有還沒 commit 的改動，它們不會被測到（只測 $(git rev-parse --short HEAD)）。" >&2
fi

if ! docker info >/dev/null 2>&1; then
    echo "連不到 Docker。先把 Docker Desktop 打開再跑（pre-push 時要跳過檢查：git push --no-verify）。" >&2
    exit 1
fi

echo "── 準備 CI 映像檔 ──"
# build context 也從那個 commit 取，而且只給 ci/Dockerfile 會 COPY 的檔案：這幾個檔案沒變，整個映像檔都用快取
git archive "$commit" ci/Dockerfile backend/gradlew backend/gradle/wrapper web/package.json \
    | docker build --quiet --tag "$image" --file ci/Dockerfile - >/dev/null

trap 'docker rm --force "$container" >/dev/null 2>&1 || true' EXIT

# docker.sock：Testcontainers 開的 PostgreSQL 跟這個容器並排開在主機的 Docker 上，不在容器裡再包一層 Docker。
#   所以 TESTCONTAINERS_HOST_OVERRIDE 要指向主機：資料庫的埠發佈在主機上，localhost 是這個容器自己
# host.docker.internal：容器裡找主機的名字。Docker Desktop 內建，Linux（GitHub 的 runner）要 --add-host 才有
# 兩個 volume 是依賴快取，跨次保留，不然每次都重新下載幾百 MB。Gradle 只掛 caches/：Gradle 本體裝在映像檔的 wrapper/，掛整個家目錄會蓋掉 wrapper/
# SPRING_AI_OPENAI_CHAT_BASEURL：模型測試連的 llama-server，預設是主機的 8080。連不到的話那些測試跳過，不算失敗
docker create --name "$container" \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume talkcal-ci-gradle:/opt/gradle-home/caches \
    --volume talkcal-ci-pnpm:/cache/pnpm-store \
    --add-host host.docker.internal:host-gateway \
    --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    --env RUN_EVAL="${RUN_EVAL:-0}"     --env SPRING_AI_OPENAI_CHAT_BASEURL="${SPRING_AI_OPENAI_CHAT_BASEURL:-http://host.docker.internal:8080/v1}" \
    "$image" ci/checks.sh >/dev/null

echo "── 複製 $(git rev-parse --short "$commit") 的程式碼進容器 ──"
git archive "$commit" | docker cp - "$container:/work"

status=0
docker start --attach "$container" || status=$?

# 前面某一步沒執行到的話，對應的報告不存在，跳過不算錯。
# 前端 E2E 的報告這樣開：pnpm --dir web exec playwright show-report ../ci/build/playwright-report
rm -rf "$out"
mkdir -p "$out"
docker cp "$container:/work/ci/build/summary.md" "$out/summary.md" >/dev/null 2>&1 || true
docker cp "$container:/work/backend/build/reports" "$out/reports" >/dev/null 2>&1 || true
docker cp "$container:/work/backend/build/eval" "$out/eval" >/dev/null 2>&1 || true
docker cp "$container:/work/web/playwright-report" "$out/playwright-report" >/dev/null 2>&1 || true
docker cp "$container:/work/web/coverage" "$out/web-coverage" >/dev/null 2>&1 || true

if [ $status -eq 0 ]; then
    echo "── CI 通過 ──"
else
    echo "── CI 失敗：報告在 $out/reports/ ──" >&2
fi
exit $status
