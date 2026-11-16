#!/usr/bin/env bash
# 用 Trivy 掃已知漏洞（CVE）和危險的設定，有 HIGH、CRITICAL 就 exit 1。
#
#   ci/vulns.sh --repo <自己 build 的映像檔…> $(ci/compose-images.sh)   完整指令見 ci/README.md「漏洞掃描」
#   ci/vulns.sh $(ci/compose-images.sh)                                 只掃 EC2 上的第三方映像檔
#
# --repo 先掃 repo 本身，再掃後面給的映像檔：
#   - 設定：Dockerfile、compose、Terraform。Trivy 沒有 compose 的規則，自己寫在 ci/config-checks/
#   - 前端的 lockfile：前端映像檔裡只剩 nginx 和打包好的靜態檔，掃映像檔看不出用了哪些套件
# 後端不另外掃 build.gradle：jar 裡就是 Gradle 解析完的版本，掃映像檔就掃到了。
#
# 漏洞只算已經有修補版本的（--ignore-unfixed）：上游還沒修的也沒得升，擋下來只會讓每個 PR 都紅。
# 評估過決定接受的寫進 ci/trivyignore.yaml。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 別讓 Git Bash 改寫 /var/run/docker.sock（見 ci/run.sh）

# 映像檔的版本和 digest 從 ci/Dockerfile 讀，版本只維護那一處（理由見 ci/Dockerfile 的 Trivy 那段）。
# 釘 digest、不用 trivy-action：tag 可以被換成別的內容，digest 不行。
# 2026-03 Trivy 被入侵時，v0.69.4 和 trivy-action 的 tag 就被換成會偷 secret 的版本。
# 只比對 COPY --from 開頭的那一行，註解裡寫到同一個名字才不會多抓一筆
image=$(sed -nE 's|^COPY --from=(aquasec/trivy:[^ ]+) .*|\1|p' ci/Dockerfile)
if [ -z "$image" ] || [ "$(printf '%s\n' "$image" | wc -l)" -ne 1 ]; then
    echo "ci/Dockerfile 裡找不到唯一一行 COPY --from=aquasec/trivy:…" >&2
    exit 1
fi

# Windows 上 docker 要 C:/... 形式的路徑（pwd -W），Linux、macOS 沒有 -W 就用 pwd
repo=$(pwd -W 2>/dev/null || pwd)

status=0
out=ci/build/vulns.md
mkdir -p ci/build
echo "## Trivy 掃描（HIGH、CRITICAL；漏洞只算有修補版本的）" > "$out"

scan() { # scan <標題> <trivy 子指令和參數…>：結果印出來，也寫進報告
    local title=$1 code=0 report sock=()
    shift
    echo "── $title ──"
    # docker.sock 只給掃映像檔的：讓 Trivy 直接讀本機 Docker 裡的映像檔。docker.sock 等同主機 root
    # （見 ci/config-checks/compose_docker_socket.rego），只讀檔案的掃描不給
    if [ "$1" = image ]; then sock=(--volume /var/run/docker.sock:/var/run/docker.sock); fi
    # talkcal-trivy-cache 存漏洞資料庫（幾十 MB），本機跨次保留
    report=$(docker run --rm \
        --volume talkcal-trivy-cache:/root/.cache/trivy \
        "${sock[@]}" \
        --volume "$repo:/src:ro" \
        "$image" "$@" --quiet --severity HIGH,CRITICAL \
        --ignorefile /src/ci/trivyignore.yaml --exit-code 1 --format table) || code=$?
    echo "$report"
    { echo; echo "### $title"; echo; echo '```'; echo "$report"; echo '```'; } >> "$out"
    # 1 是有問題，其他非 0 是 Trivy 自己出錯（例如映像檔不存在），兩種都算失敗。
    # 一個失敗不提早結束，每個都掃完。寫成 if：[ ] && ... 當函式最後一行時，false 會讓函式回 1，set -e 就會在這裡結束整支腳本
    if [ "$code" -ne 0 ]; then status=1; fi
}

vuln=(--scanners vuln --ignore-unfixed)

if [ "${1:-}" = --repo ]; then
    shift
    # --skip-dirs：本機才有的 build 產物和別人的程式碼，CI 上 git archive 出來的沒有。
    # --misconfig-scanners 的 yaml：所有 yaml 都交給 ci/config-checks/ 的規則，規則只看有頂層 services 的（compose 檔）。
    #   --check-namespaces user 是那些規則的 package 前綴
    # --skip-check-update：只用跟著 Trivy 版本內建的規則。執行時下載最新規則包的話，
    #   Aqua 發新規則那天程式碼沒變、所有 PR 都會突然紅，本機和 CI 也可能拿到不同版本
    scan "設定：Dockerfile、compose、Terraform" config \
        --skip-check-update \
        --misconfig-scanners dockerfile,terraform,yaml \
        --config-check /src/ci/config-checks --check-namespaces user \
        --skip-dirs '**/node_modules' --skip-dirs '**/build' --skip-dirs '**/.terraform' \
        --skip-dirs .git --skip-dirs .claude \
        /src

    # 只給 lockfile：給整個 web/ 的話，本機的 node_modules 有幾萬個檔案，掛進容器讀很慢，也用不到。
    # --include-dev-deps：Trivy 預設跳過 devDependencies，而前端的套件幾乎都是。
    #   這些套件不上線，但在開發者的電腦和 CI 上執行，開發伺服器的漏洞（例如 vite 的 CVE-2026-112942）照樣會被利用
    scan "web/pnpm-lock.yaml" fs "${vuln[@]}" --include-dev-deps /src/web/pnpm-lock.yaml
fi

for target in "$@"; do
    scan "$target" image "${vuln[@]}" "$target"
done

exit $status
