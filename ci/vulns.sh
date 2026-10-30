#!/usr/bin/env bash
# 掃有沒有已知漏洞（CVE）。用 Trivy，在容器裡跑。
#
#   ci/vulns.sh talkcal/app:local talkcal/web:local postgres:18.6-alpine
#   ci/vulns.sh --images-only $(ci/compose-images.sh)    只掃上線的第三方映像檔（caddy、觀測堆疊…）
#
# 掃兩種東西：
#   - 前端的 lockfile（web/pnpm-lock.yaml）：前端映像檔裡只剩 nginx 和打包好的靜態檔，
#     vite 這類開發工具、打包進 js 的套件都已經看不出是哪個套件哪一版，掃映像檔抓不到（#235）。
#     不用帶參數，每次都掃
#   - 參數給的映像檔：映像檔才是真的會上線的東西，一次看得到兩種 ——
#     基底映像檔的作業系統套件（openssl、curl…），和 jar 裡打包的 Java 函式庫（Tomcat、Jackson…）。
#     後端不另外掃 build.gradle：jar 裡就是 Gradle 解析完的確切版本，掃映像檔已經涵蓋
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
# 所以不用 trivy-action、不用會移動的 tag，用的是事件之後發的版本
image=aquasec/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969

# Windows 上 docker 要 C:/... 形式的路徑（pwd -W），Linux、macOS 沒有 -W 就用 pwd
repo=$(pwd -W 2>/dev/null || pwd)

status=0
out=ci/build/vulns.md
mkdir -p ci/build
echo "## 漏洞和設定掃描（HIGH、CRITICAL；漏洞只算有修補版本的）" > "$out"

scan() { # scan <標題> <trivy 子指令和參數…>：跑一次 Trivy，結果印出來也寫進報告
    local title=$1 code=0 report
    shift
    echo "── $title ──"
    # talkcal-trivy-cache：漏洞資料庫（幾十 MB），本機跨次保留；GitHub 每次都是新機器，會重新下載
    # 一個有漏洞不提早結束：每個都掃完，一次看到全部（跟 checks.sh 同一個理由）
    report=$(docker run --rm \
        --volume talkcal-trivy-cache:/root/.cache/trivy \
        --volume "$repo/ci/trivyignore.yaml:/trivyignore.yaml:ro" \
        "${mounts[@]}" \
        "$image" "$@" --quiet --severity HIGH,CRITICAL \
        --ignorefile /trivyignore.yaml --exit-code 1 --format table) || code=$?
    echo "$report"
    { echo; echo "### $title"; echo; echo '```'; echo "$report"; echo '```'; } >> "$out"
    # 1 是「有漏洞」；其他非 0 是 Trivy 自己出錯（例如映像檔不存在）—— 兩種都算失敗，不能當成「沒漏洞」。
    # 寫成 if 而不是 [ ] && ...：函式最後一行是 false 的話函式回傳 1，set -e 會讓整支在這裡結束
    if [ "$code" -ne 0 ]; then status=1; fi
}

# 漏洞掃描共用的參數（設定掃描沒有這兩個）
vuln=(--scanners vuln --ignore-unfixed)

scan_repo() { # 掃 repo 本身：設定、前端 lockfile
    # 掛整個 repo（唯讀），跳過不是自己寫的、或 build 出來的資料夾（本機才有，git archive 出來的 CI 上沒有）。
    # --misconfig-scanners 加 yaml、--file-patterns：Trivy 沒有 compose 的掃描器，把 compose 檔當一般的 yaml，
    #   交給 ci/config-checks/ 的規則；--check-namespaces user 是那些自己寫的 package 的前綴
    mounts=(--volume "$repo:/src:ro" --volume "$repo/ci/config-checks:/checks:ro")
    scan "設定：Dockerfile、compose、Terraform" config \
        --misconfig-scanners dockerfile,terraform,yaml --file-patterns 'yaml:compose[^/]*\.ya?ml$' \
        --config-check /checks --check-namespaces user \
        --skip-dirs '**/node_modules' --skip-dirs '**/build' --skip-dirs '**/.terraform' \
        --skip-dirs .git --skip-dirs .claude \
        /src

    # 只掛 lockfile 一個檔案，不掛整個 web/：本機的 node_modules 有幾萬個檔案，Windows 掛進容器很慢，也用不到
    # --include-dev-deps：Trivy 預設跳過 devDependencies，而前端的套件幾乎都是（vite、vitest…），不加等於沒掃。
    #   它們不會上線，但在開發者的電腦和 CI 上執行，開發伺服器的漏洞（例如 vite 的 CVE-2026-112942）照樣打得到
    # Trivy 也認得 package-lock.json、yarn.lock，換套件管理工具時改這裡的檔名就好
    mounts=(--volume "$repo/web/pnpm-lock.yaml:/src/web/pnpm-lock.yaml:ro")
    scan "web/pnpm-lock.yaml" fs "${vuln[@]}" --include-dev-deps /src/web
}

# --images-only：只掃參數給的映像檔。ci.yml 的 third-party-images job 用：設定和 lockfile 已經在 vulns job 掃過了
if [ "${1:-}" = --images-only ]; then shift; else scan_repo; fi

# docker.sock：讓 Trivy 直接讀本機 Docker 裡的映像檔，不必先推到 registry
mounts=(--volume /var/run/docker.sock:/var/run/docker.sock)
for target in "$@"; do
    scan "$target" image "${vuln[@]}" "$target"
done

exit $status
