#!/usr/bin/env bash
# CI 要檢查什麼 —— 唯一定義的地方。在 ci/Dockerfile 的容器裡跑（由 ci/run.sh 啟動）。
#
# 為什麼獨立成一支 script：GitHub Actions、pre-push hook，甚至換成 GitLab、Jenkins，
# 都只是「在某個時機呼叫這支」。要加一項檢查只改這裡，各個入口自動一致。
#
# 每一項都跑完才結束，不在第一個失敗就停：後端紅了，前端的結果照樣有參考價值，
# 一次看到全部的問題，比修一個、推一次、再看下一個快。

set -uo pipefail
cd "$(dirname "$0")/.."

status=0

echo "── 後端：單元測試、架構規則、整合測試 ──"
# 整合測試裡要 PostgreSQL 的那幾支用 Testcontainers 開在旁邊（run.sh 把主機的 Docker 接進來了）；
# 要真模型的那幾支，連不到 llama-server 就自動跳過（Assumptions），下面的摘要會列出來
(cd backend && ./gradlew --no-daemon test integrationTest) || status=1

echo "── 前端：型別檢查、打包 ──"
# npm ci：照 package-lock.json 原樣裝，lock 跟 package.json 對不上就直接失敗
(cd web && npm ci --no-audit --no-fund && npm run build) || status=1

# ── 測試摘要 ─────────────────────────────────────────────────────
# 為什麼要特別列出「跳過」：綠燈只代表「跑了的都過了」。
# 模型沒開時，AI 相關的測試全部跳過，綠燈卻看不出來 —— 不寫出來，就會被誤讀成「全部都測過了」。
summary=ci/build/summary.md
mkdir -p ci/build

count() { # count <目錄> <屬性>：把那個目錄所有 testsuite 的某個屬性加總
    cat "$1"/*.xml 2>/dev/null | grep -o "<testsuite [^>]*" | grep -o " $2=\"[0-9]*\"" | grep -o "[0-9]*" \
        | awk '{ s += $1 } END { print s + 0 }'
}

skipped_list() { # 列出被跳過的測試：類別 › 名稱
    for f in backend/build/test-results/test/*.xml backend/build/test-results/integrationTest/*.xml; do
        [ -f "$f" ] || continue
        awk '
            /<testcase / {
                # 前面帶空白，才不會配到 classname=" 裡的 name="
                match($0, / name="[^"]*"/);  name = substr($0, RSTART + 7, RLENGTH - 8)
                match($0, /classname="[^"]*"/); cls = substr($0, RSTART + 11, RLENGTH - 12)
                n = split(cls, parts, "."); cls = parts[n]
            }
            /<skipped/ { print "- " cls " › " name }
        ' "$f"
    done
}

{
    echo "## 測試結果"
    echo
    echo "| | 測試數 | 失敗 | 跳過 |"
    echo "|---|---|---|---|"
    for kind in test integrationTest; do
        dir=backend/build/test-results/$kind
        label=$([ "$kind" = test ] && echo "單元測試" || echo "整合測試")
        failed=$(( $(count "$dir" failures) + $(count "$dir" errors) ))
        echo "| $label | $(count "$dir" tests) | $failed | $(count "$dir" skipped) |"
    done
    echo
    skipped=$(skipped_list)
    if [ -n "$skipped" ]; then
        echo "### 跳過的測試"
        echo
        echo "通常是連不到 llama-server（沒有模型）。這些測試**沒有被驗證**，綠燈不包含它們。"
        echo
        echo "$skipped"
    else
        echo "沒有跳過任何測試。"
    fi
} > "$summary"

echo
cat "$summary"
exit $status
