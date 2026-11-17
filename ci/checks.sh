#!/usr/bin/env bash
# CI 的檢查項目，在 ci/Dockerfile 的容器裡執行（由 ci/run.sh 啟動）。要加一項檢查就加在這裡。
#
# 不設 -e：一項失敗還是往下執行，每一項都執行完再用 status 決定成敗。後端失敗了，前端的結果照樣有用，
# 一次看到全部的問題，不必修一個、推一次、再看下一個。

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

status=0

echo "── shell 腳本：ShellCheck ──"
# 用 find 不用 git ls-files：容器裡的程式碼是 git archive 出來的，沒有 .git。
# 放在 pnpm install 之前，node_modules 裡別人的腳本才不會被掃到。
# 要保留被警告的寫法，在那一行上面加 # shellcheck disable=SCxxxx 並寫理由（例子見 cd/triggers/watch.sh 的 trap）
find . \( -name '*.sh' -o -path './.githooks/*' \) -type f -print0 | xargs -0 shellcheck || status=1

echo "── 架構圖：每個錨點都對得上檔案 ──"
# 錨點的規則見 docs/architecture/lines.mjs 開頭。lines.mjs 只用 Node 內建模組，不必等 pnpm install
node docs/architecture/lines.mjs --check || status=1

# 前端和後端互不相依，同時執行：這一段花的時間是比較久的那一邊（後端），不是兩邊加起來。
# 前端的每一行加上「[前端]」再印，跟後端的行交錯時分得出來。不先存檔、等後端結束才印：
# 前端卡住、整個 job 逾時被中止的話，存起來的輸出就印不出來，看不出卡在哪一步
frontend() {
    local s=0
    echo "── 前端：lint、單元測試、型別檢查、打包、E2E ──"
    # 照快到慢排，前面失敗後面就不執行。型別錯誤只有 build（vue-tsc）抓得到，所以 build 排在 E2E 前面。
    # E2E 的 API 是假的，不需要後端
    (cd web && pnpm install --frozen-lockfile && pnpm run lint && pnpm test && pnpm run build && pnpm run e2e) || s=1

    echo "── 授權：前端會打包的套件 ──"
    ci/licenses.sh || s=1
    return $s
}
# $! 必須是 frontend 本身：接 | sed 的話 $! 是 sed，wait 拿到的是 sed 的結束碼，前端失敗時 CI 仍然會通過
frontend > >(sed -u 's/^/[前端] /') 2>&1 &
frontend_pid=$!

echo "── 後端：單元測試、架構規則、整合測試（編譯時 Error Prone 一起跑）──"
# 要真模型的測試連不到 llama-server 就跳過，下面的摘要會列出來。
# evalClasses：AI eval 要真模型、執行一分多鐘，不在這裡執行，但要編譯得過；
#   不然改了 CalendarAssistant 的方法簽名，eval 壞了要等下次有人執行才會發現
# jacocoTestReport：覆蓋率只記錄、不設門檻，總數貼在下面的摘要
# checkLicense：會打包進 jar 的依賴有沒有不允許的授權，規則見 docs/licenses.md
(cd backend && ./gradlew --no-daemon test integrationTest evalClasses jacocoTestReport checkLicense) || status=1

# RUN_EVAL=1 由 .githooks/pre-push 在改到 EVAL_PATHS 列的檔案（prompt、模型設定、eval）時設定。GitHub 上沒有模型，不會設。
# 只有基準線裡的題目沒過才算失敗；報告在 backend/build/eval/report.md，ci/run.sh 會複製回 ci/build/eval/
if [ "${RUN_EVAL:-0}" = 1 ]; then
    echo "── AI eval：這次改到 prompt 或模型相關的檔案 ──"
    (cd backend && ./gradlew --no-daemon eval) || status=1
fi

wait "$frontend_pid" || status=1

# ── 測試摘要 ─────────────────────────────────────────────────────
# 跳過的測試要另外列出來：模型沒開時，要真模型的測試全部跳過，綠燈看起來卻跟全部測過一樣
summary=ci/build/summary.md
mkdir -p ci/build

count() { # count <目錄> <屬性>：那個目錄所有 testsuite 的某個屬性加總
    cat "$1"/*.xml 2>/dev/null | grep -o "<testsuite [^>]*" | grep -o " $2=\"[0-9]*\"" | grep -o "[0-9]*" \
        | awk '{ s += $1 } END { print s + 0 }'
}

skipped_list() { # 被跳過的測試，一行一個：類別 › 名稱
    for f in backend/build/test-results/test/*.xml backend/build/test-results/integrationTest/*.xml; do
        [ -f "$f" ] || continue
        awk '
            /<testcase / {
                # name= 前面要帶空白，不然會配到 classname=" 裡的 name="
                match($0, / name="[^"]*"/);  name = substr($0, RSTART + 7, RLENGTH - 8)
                match($0, /classname="[^"]*"/); cls = substr($0, RSTART + 11, RLENGTH - 12)
                n = split(cls, parts, "."); cls = parts[n]
            }
            /<skipped/ { print "- " cls " › " name }
        ' "$f"
    done
}

backend_coverage() { # 後端的「行 | 分支」兩欄，從 JaCoCo 的 CSV 加總
    # 欄位：GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,…
    local csv=backend/build/reports/jacoco/test/jacocoTestReport.csv
    [ -f "$csv" ] || { echo "（沒有報告） | （沒有報告）"; return; }
    awk -F, '
        function pct(c, t) { return sprintf("%.1f%% (%d/%d)", t ? 100 * c / t : 0, c, t) }
        NR > 1 { bm += $6; bc += $7; lm += $8; lc += $9 }
        END { print pct(lc, lm + lc) " | " pct(bc, bm + bc) }
    ' "$csv"
}

web_coverage() { # 前端的「行 | 分支」兩欄，讀 Vitest 的 json-summary
    local json=web/coverage/coverage-summary.json
    [ -f "$json" ] || { echo "（沒有報告） | （沒有報告）"; return; }
    # shellcheck disable=SC2016  # 單引號裡是 JavaScript，${} 是 JS 的模板字串，本來就不該給 shell 展開
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

    # 怎麼看覆蓋率報告見 docs/test-coverage.md
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
