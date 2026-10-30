#!/usr/bin/env bash
# 在 ci/sast/Dockerfile 的容器裡跑 CodeQL（由 ci/sast.sh 啟動）。/work 是要掃的那個 commit 的程式碼。
#
# 掃三種語言，都不編譯（build-mode none）：
#   java-kotlin            後端。CodeQL 直接讀原始碼，但會跑 ./gradlew 拿依賴清單、下載 jar，
#                          外部函式庫的型別才認得出來（拿不到時 JdbcClient 拼 SQL 會漏掉，見 ci/sast/Dockerfile）
#   javascript-typescript  前端的 .ts 和 .vue 的 <script>（模板裡的 v-html 追不到，由 ESLint 的 vue/no-v-html 擋）
#   actions                .github/workflows/：${{ }} 直接貼進 run 的注入、PR 觸發卻拿到寫入權限這類問題
#
# 用的是各語言的預設查詢（code-scanning 那一組，跟 GitHub 的 default setup 一樣）：只收誤判少的。
# security-extended 會多出不少「可能」的結果，每個都要人判斷，先不開。

set -euo pipefail

out=/work/ci/build/sast
mkdir -p "$out"
langs=(java-kotlin javascript-typescript actions)
# security-severity（CVSS 分數）到這個值以上就紅燈：7.0 = HIGH，跟 ci/vulns.sh 的 HIGH、CRITICAL 同一條線
threshold=7

# --db-cluster：一次建好三種語言的資料庫，各自一個子目錄
# codeql-config.yml：不掃的路徑和理由
codeql database create /tmp/db --db-cluster --source-root=/work --build-mode=none \
    --codescanning-config=/work/ci/sast/codeql-config.yml \
    --language="$(IFS=,; echo "${langs[*]}")" --threads=0 --quiet

for lang in "${langs[@]}"; do
    # 資料庫目錄和查詢包都用短名字：java-kotlin 的在 /tmp/db/java、codeql/java-queries
    lang=${lang%%-*}
    models=()
    # ci/sast/models/ 補的 API 模型（JdbcClient）
    if [ "$lang" = java ]; then
        models=(--additional-packs=/work/ci/sast/models --model-packs=talkcal/java-models)
    fi
    codeql database analyze "/tmp/db/$lang" "codeql/$lang-queries" "${models[@]}" \
        --format=sarif-latest --output="$out/$lang.sarif" --threads=0 --quiet
done

# 每一筆結果配上它的規則的 security-severity，印成一行一筆：分數<TAB>規則<TAB>檔案:行<TAB>訊息
# 規則可能在 driver（CLI 本身）或 extensions（查詢包）裡，兩邊都找
jq -r '
    .runs[]
    | ([.tool.driver, .tool.extensions[]?] | [.[].rules[]?]
        | map({key: .id, value: .properties["security-severity"]}) | from_entries) as $sev
    | .results[]
    | [($sev[.ruleId] // "0" | tonumber),
       .ruleId,
       (.locations[0].physicalLocation | "\(.artifactLocation.uri):\(.region.startLine)"),
       (.message.text | gsub("\n"; " ") | gsub("\|"; "\|"))]  # | 會切斷下面摘要的 markdown 表格
    | @tsv
' "$out"/*.sarif | sort -rn > "$out/results.tsv"

# 摘要（GitHub 貼到結果頁）：全部列出，門檻以上的標 ❌、讓 CI 紅。最後一行是擋下的筆數
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
