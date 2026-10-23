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
#   - 依賴快取（具名 volume talkcal-ci-gradle-ro、talkcal-ci-npm）：不共用的話每次都要重新下載幾百 MB。
#     Gradle 本體不在這裡，它烤在映像檔裡（見 ci/Dockerfile）。
#     Gradle 那份是唯讀掛進去的，好幾個 CI 同時跑才不會搶鎖（#109，見下面 docker create 那段）
#   - Docker（docker.sock）：讓 Testcontainers 開 PostgreSQL。開在主機上、跟這個容器並排，而不是容器裡再包一層
#   - 主機上的 llama-server（host.docker.internal:8080）：有開就真的跑模型測試，沒開就跳過

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

# Windows 的 Git Bash 會把看起來像路徑的參數（/var/run/docker.sock、/work）改寫成 C:/Program Files/Git/...，
# 傳給 docker 就全錯了。關掉這個改寫；在 Linux、macOS 上這個變數沒有作用
export MSYS_NO_PATHCONV=1

commit=$(git rev-parse --verify "${1:-HEAD}^{commit}")
# 這個容器最多用幾顆 CPU（#109）。不限的話一個 CI 會吃滿整台，好幾個 worktree 同時跑就互相拖慢、電腦也卡住。
# 數字怎麼來的（量了一次 CI 用掉多少）見 docs/parallel-dev.md。checks.sh 裡的 Gradle、Vitest、Playwright 也照這個數開 worker：
# --cpus 只限總量、不改容器看到的核心數，Node 那幾個工具照「看到的核心數」開 worker 的話，會開出一堆搶同一點 CPU 的程序
cpus=${CI_CPUS:-2}
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
git archive "$commit" ci/Dockerfile backend/gradlew backend/gradle/wrapper web/package.json \
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
#
# Gradle 的依賴快取（#109）：以前整個 caches/ 是一個可寫的共用 volume，兩個 worktree 同時跑 CI 時 Gradle 會失敗：
#   Timeout waiting to lock journal cache (/opt/gradle-home/caches/journal-1). It is currently in use by another process.
# Gradle 的跨程序鎖要「拿著鎖的那個程序」放手，請它放手的通知走 127.0.0.1 的 UDP；
# 兩個 CI 在不同容器、各有各的 127.0.0.1，通知送不到，另一個只能乾等到逾時。
# 改成 Gradle 給 CI 用的唯讀快取（GRADLE_RO_DEP_CACHE）：共用的那份唯讀掛進來，唯讀的不加鎖，幾個容器同時讀都沒事；
# 唯讀快取裡沒有的（新加的依賴）照樣下載，寫進這個容器自己的 caches/，跟著容器一起丟掉。
# 下載到的新檔在跑完之後併回共用的那份（最下面），下次就不必再下載
docker create --name "$container" \
    --cpus "$cpus" --env CI_CPUS="$cpus" \
    --volume /var/run/docker.sock:/var/run/docker.sock \
    --volume talkcal-ci-gradle-ro:/opt/gradle-ro:ro \
    --env GRADLE_RO_DEP_CACHE=/opt/gradle-ro \
    --volume talkcal-ci-npm:/cache/npm \
    --add-host host.docker.internal:host-gateway \
    --env TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
    --env RUN_EVAL="${RUN_EVAL:-0}" \
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
docker cp "$container:/work/backend/build/eval" "$out/eval" >/dev/null 2>&1 || true
# 前端 E2E 失敗時的 HTML 報告（每一步的截圖、trace）：npx playwright show-report ci/build/playwright-report
docker cp "$container:/work/web/playwright-report" "$out/playwright-report" >/dev/null 2>&1 || true

# 這次新下載的依賴併回共用的唯讀快取（Gradle 文件說的做法：複製 modules-2，不要鎖檔和 gc.properties）。
# 別的 CI 可能正在讀那份，所以只新增、不改已經有的檔案，而且每個檔先寫到旁邊再 mv（同一個檔案系統裡 mv 是一瞬間的事）：
# 讀的人只會看到「沒有」或「完整的」，不會讀到寫一半的 jar。兩個 CI 同時併同一個檔也沒關係，內容一樣、誰的 mv 留下來都對。
# 失敗不影響 CI 結果：頂多下次再下載一次
echo "── 新下載的 Gradle 依賴併回共用快取 ──"
docker cp "$container:/opt/gradle-home/caches/modules-2" - 2>/dev/null \
    | docker run --rm -i --volume talkcal-ci-gradle-ro:/ro --entrypoint bash "$image" -c '
        set -euo pipefail
        staging=/ro/.staging-$HOSTNAME
        mkdir -p "$staging" && tar -x -C "$staging"
        cd "$staging"
        added=0
        while IFS= read -r -d "" f; do
            [ -e "/ro/$f" ] && continue
            mkdir -p "/ro/$(dirname "$f")"
            mv "$f" "/ro/$f"
            added=$((added + 1))
        done < <(find . -type f ! -name "*.lock" ! -name gc.properties -print0)
        rm -rf "$staging"
        echo "新增 $added 個檔案"
    ' || echo "（沒併成功，不影響這次的結果）" >&2

if [ $status -eq 0 ]; then
    echo "── CI 通過 ──"
else
    echo "── CI 失敗：報告在 $out/reports/ ──" >&2
fi
exit $status
