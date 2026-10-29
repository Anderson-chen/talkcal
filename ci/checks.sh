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

echo "── 後端：單元測試、架構規則、整合測試（編譯時 Error Prone 一起跑）──"
# 整合測試裡要 PostgreSQL 的那幾支用 Testcontainers 開在旁邊（run.sh 把主機的 Docker 接進來了）；
# 要真模型的那幾支，連不到 llama-server 就自動跳過（Assumptions），下面的摘要會列出來
# evalClasses：AI eval（./gradlew eval）不在這裡跑（要真模型、跑一分多鐘），但至少要編得過，
# 不然改了 CalendarAssistant 的方法簽名，eval 壞了沒人知道，直到下次有人想跑它
# jacocoTestReport：單元＋整合測試合併的覆蓋率報告（#23），不會讓 CI 紅，總數貼在下面的摘要
(cd backend && ./gradlew --no-daemon test integrationTest evalClasses jacocoTestReport) || status=1

# AI eval：只有 pre-push 發現這次改到 prompt 或模型相關的檔案時才跑（它設 RUN_EVAL=1，見 .githooks/pre-push）。
# GitHub 上不跑：沒有模型。基準線裡的題目沒過才會紅；報告在 backend/build/eval/report.md（run.sh 會拿回 ci/build/eval/）
if [ "${RUN_EVAL:-0}" = 1 ]; then
    echo "── AI eval：這次改到 prompt 或模型相關的檔案 ──"
    (cd backend && ./gradlew --no-daemon eval) || status=1
fi

echo "── 前端：lint、單元測試、型別檢查、打包、E2E ──"
# pnpm install --frozen-lockfile：照 pnpm-lock.yaml 原樣裝，lock 跟 package.json 對不上就直接失敗
# pnpm run lint：ESLint，抓會出錯的寫法（忘了 await、v-for 沒 key…）。幾秒就有結果，放在測試前面
# pnpm test：Vitest，只測純函式（秒回）
# pnpm run e2e：Playwright 開真的 Chromium（桌面、手機兩種版面），API 用假的，不需要後端。
#   它會自己 build 再起 vite preview；放在 build 之後，型別錯的話先在 build 那一步就停
(cd web && pnpm install --frozen-lockfile && pnpm run lint && pnpm test && pnpm run build && pnpm run e2e) || status=1

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

backend_coverage() { # 後端的「行 | 分支」兩欄：把 JaCoCo CSV 每個類別的那一列加總
    # 欄位：GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,…
    local csv=backend/build/reports/jacoco/test/jacocoTestReport.csv
    [ -f "$csv" ] || { echo "（沒有報告） | （沒有報告）"; return; }
    awk -F, '
        function pct(c, t) { return sprintf("%.1f%% (%d/%d)", t ? 100 * c / t : 0, c, t) }
        NR > 1 { bm += $6; bc += $7; lm += $8; lc += $9 }
        END { print pct(lc, lm + lc) " | " pct(bc, bm + bc) }
    ' "$csv"
}

web_coverage() { # 前端的「行 | 分支」兩欄：讀 Vitest 的 json-summary
    local json=web/coverage/coverage-summary.json
    [ -f "$json" ] || { echo "（沒有報告） | （沒有報告）"; return; }
    node -e 'const t = require(process.argv[1]).total
        const pct = (k) => `${t[k].pct}% (${t[k].covered}/${t[k].total})`
        console.log(`${pct("lines")} | ${pct("branches")}`)' "$PWD/$json"
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

    # 覆蓋率（#23）：只列總數，不設門檻。要看哪一行沒跑到、報告在哪，見 docs/test-coverage.md
    echo
    echo "## 測試覆蓋率"
    echo
    echo "| | 行 | 分支 | 報告 |"
    echo "|---|---|---|---|"
    echo "| 後端（單元＋整合） | $(backend_coverage) | \`reports/jacoco/test/html/\` |"
    echo "| 前端（Vitest，只算 .ts） | $(web_coverage) | \`web-coverage/\` |"
} > "$summary"

echo
cat "$summary"
exit $status
