#!/usr/bin/env bash
# 在 ci/sast/Dockerfile 的容器裡執行 CodeQL（由 ci/sast.sh 啟動），/work 是要掃的程式碼。
#
# 三種語言都不編譯（build-mode none）：
#   java-kotlin            後端。CodeQL 還是會執行 ./gradlew 下載依賴，才解析得到外部函式庫的型別（見 ci/sast/Dockerfile）
#   javascript-typescript  前端的 .ts 和 .vue 的 <script>。模板裡的 v-html 追不到，由 ESLint 的 vue/no-v-html 擋
#   actions                .github/workflows/：${{ }} 直接貼進 run 的注入、PR 觸發卻拿到寫入權限這類問題
#
# 查詢用各語言的預設組（跟 GitHub code scanning 的 default setup 同一組），只收誤判少的。
# security-extended 會多出很多要人判斷的「可能」，先不開。

set -euo pipefail

out=/work/ci/build/sast
mkdir -p "$out"
langs=(java-kotlin javascript-typescript actions)
# security-severity（CVSS 分數）到這個值就紅燈：7.0 是 HIGH，跟 ci/vulns.sh 擋 HIGH、CRITICAL 同一條線
threshold=7

# codeql-config.yml 寫了不掃的路徑和理由
codeql database create /tmp/db --db-cluster --source-root=/work --build-mode=none \
    --codescanning-config=/work/ci/sast/codeql-config.yml \
    --language="$(IFS=,; echo "${langs[*]}")" --threads=0 --quiet

for lang in "${langs[@]}"; do
    # --db-cluster 建的資料庫目錄和查詢包都用短名字：java-kotlin 是 /tmp/db/java、codeql/java-queries
    lang=${lang%%-*}
    models=()
    # CodeQL 沒建模的 JdbcClient，模型在 ci/sast/models/
    if [ "$lang" = java ]; then
        models=(--additional-packs=/work/ci/sast/models --model-packs=talkcal/java-models)
    fi
    codeql database analyze "/tmp/db/$lang" "codeql/$lang-queries" "${models[@]}" \
        --format=sarif-latest --output="$out/$lang.sarif" --threads=0 --quiet
done

# 每一筆結果配上所屬規則的 security-severity，一行一筆：分數<TAB>規則<TAB>檔案:行<TAB>訊息。
# 規則可能在 driver（CLI 本身）或 extensions（查詢包）裡，兩邊都找
jq -r '
    .runs[]
    | ([.tool.driver, .tool.extensions[]?] | [.[].rules[]?]
        | map({key: .id, value: .properties["security-severity"]}) | from_entries) as $sev
    | .results[]
    | [($sev[.ruleId] // "0" | tonumber),
       .ruleId,
       (.locations[0].physicalLocation | "\(.artifactLocation.uri):\(.region.startLine)"),
       (.message.text | gsub("\n"; " ") | gsub("\\|"; "\\|"))]  # | 會切斷下面摘要的 markdown 表格
    | @tsv
' "$out"/*.sarif | sort -rn > "$out/results.tsv"

# 摘要全部列出，門檻以上的標 ❌
blocking=$(awk -F'\t' -v t="$threshold" -v summary="$out/summary.md" '
    BEGIN { print "## 原始碼安全分析（CodeQL）\n" > summary }
    NR == 1 { print "| security-severity | 規則 | 位置 | 說明 |\n|---|---|---|---|" > summary }
    { printf "| %s%s | `%s` | `%s` | %s |\n", $1, ($1 >= t ? " ❌" : ""), $2, $3, $4 > summary; n += ($1 >= t) }
    END { if (NR == 0) print "沒有任何結果。" > summary; print n + 0 }
' "$out/results.tsv")
cat "$out/summary.md"

if [ "$blocking" -gt 0 ]; then
    echo "有 $blocking 個 security-severity $threshold 以上（HIGH、CRITICAL）的結果。" >&2
    exit 1
fi
