#!/usr/bin/env bash
# 在乾淨的容器裡跑 CI 檢查（ci/checks.sh），跑完就把容器丟掉。
# 本機手動跑、pre-push hook、GitHub Actions 都走這一支，所以三邊跑的是同一件事、同一個環境。
#
#   ci/run.sh            測 HEAD
#   ci/run.sh <commit>   測指定的 commit（pre-push hook 用：測的是「要推上去的那個」）
#
# 跟主機隔離的方式：
#   - 程式碼：用 git archive 只複製那個 commit 的內容進容器。沒 commit 的檔案、殘留的 build/、node_modules
#             都不會進去 —— 測的就是會推上去的東西。也不掛資料夾：Windows 掛進容器很慢（約 20 MB/s）。
#   - 環境：JDK、Node 都在 ci/Dockerfile 裡，不用主機裝的版本。
#   - 跑完：容器整個刪掉，只把摘要和測試報告複製回主機的 ci/build/。
#
# 跟主機共用的只有三樣，都是刻意的：
#   - 依賴快取（具名 volume talkcal-ci-gradle、talkcal-ci-npm）：不共用的話每次都要重新下載幾百 MB。
#     Gradle 本體不在這裡，它烤在映像檔裡（見 ci/Dockerfile）
#   - Docker（docker.sock）：讓 Testcontainers 開 PostgreSQL。開在主機上、跟這個容器並排，而不是容器裡再包一層
#   - 主機上的 llama-server（host.docker.internal:8080）：有開就真的跑模型測試，沒開就跳過

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Windows 的 Git Bash 會把看起來像路徑的參數（/var/run/docker.sock、/work）改寫成 C:/Program Files/Git/...，
# 傳給 docker 就全錯了。關掉這個改寫；在 Linux、macOS 上這個變數沒有作用
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
# build context 也從那個 commit 取：只要 Dockerfile 和 Gradle wrapper 那幾個檔案，不送整個資料夾給 Docker。
# 這幾個檔案沒變時整個映像檔直接用快取，幾秒就好；升級 Gradle 後的第一次才會重新下載 Gradle 本體
git archive "$commit" ci/Dockerfile backend/gradlew backend/gradle/wrapper \
    | docker build --quiet --tag "$image" --file ci/Dockerfile - >/dev/null

# 不管成功、失敗、中途 Ctrl+C，容器都要刪掉
trap 'docker rm --force "$container" >/dev/null 2>&1 || true' EXIT

# host.docker.internal：容器裡用這個名字找主機。Docker Desktop 本來就有；
# Linux（包括 GitHub 的機器）要用 --add-host 對到 host-gateway 才有
#
# TESTCONTAINERS_HOST_OVERRIDE：Testcontainers 開的 PostgreSQL 在主機的 Docker 上，埠也發佈在主機上，
# 所以從這個容器裡要經由主機去連，而不是連 localhost（那是這個容器自己）
#
# SPRING_AI_OPENAI_CHAT_BASEURL：模型測試連哪台 llama-server（backend/build.gradle 會把它傳進測試）。
# 可以從外面覆蓋，沒給就連主機上的 8080
docker create --name "$container" \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume talkcal-ci-gradle:/opt/gradle-home/caches \
    --volume talkcal-ci-npm:/cache/npm \
    --add-host host.docker.internal:host-gateway \
    --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    --env SPRING_AI_OPENAI_CHAT_BASEURL="${SPRING_AI_OPENAI_CHAT_BASEURL:-http://host.docker.internal:8080/v1}" \
    "$image" ci/checks.sh >/dev/null

echo "── 複製 $(git rev-parse --short "$commit") 的程式碼進容器 ──"
git archive "$commit" | docker cp - "$container:/work"

status=0
docker start --attach "$container" || status=$?

# 摘要和報告拿回主機：GitHub 要貼到結果頁、上傳成 artifact；本機失敗時要打開 HTML 報告看哪一題紅
rm -rf "$out"
mkdir -p "$out"
docker cp "$container:/work/ci/build/summary.md" "$out/summary.md" >/dev/null 2>&1 || true
docker cp "$container:/work/backend/build/reports" "$out/reports" >/dev/null 2>&1 || true

if [ $status -eq 0 ]; then
    echo "── CI 通過 ──"
else
    echo "── CI 失敗：報告在 $out/reports/ ──" >&2
fi
exit $status
