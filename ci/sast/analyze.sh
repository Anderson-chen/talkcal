#!/usr/bin/env bash
# 在 ci/sast/Dockerfile 的容器裡跑 CodeQL（由 ci/sast.sh 啟動）。/work 是要掃的那個 commit 的程式碼。
#
# 掃三種語言，都不必編譯（build-mode none）：
#   java-kotlin            後端。CodeQL 直接讀原始碼推型別，不必跑 Gradle、不必下載依賴
#   javascript-typescript  前端，.vue 檔也會解析（v-html 是 XSS 的 sink）
#   actions                .github/workflows/：${{ }} 直接貼進 run 的注入、PR 觸發卻拿到寫入權限這類問題
#
# 用的是各語言的預設查詢（code-scanning 那一組，跟 GitHub 的 default setup 一樣）：只收誤判少的。
# security-extended 會多出不少「可能」的結果，每個都要人判斷，先不開。

set -euo pipefail

out=/work/ci/build/sast
mkdir -p "$out"
langs=(java-kotlin javascript-typescript actions)

# --db-cluster：一次建好三種語言的資料庫，各自一個子目錄
# codeql-config.yml：不掃的路徑和理由
codeql database create /tmp/db --db-cluster --source-root=/work --build-mode=none \
    --codescanning-config=/work/ci/sast/codeql-config.yml \
    --language="$(IFS=,; echo "${langs[*]}")" --threads=0 --quiet

for lang in "${langs[@]}"; do
    # 資料庫目錄和查詢包都用短名字：java-kotlin 的在 /tmp/db/java、codeql/java-queries
    lang=${lang%%-*}
    # --model-packs：ci/sast/models/ 補的 API 模型（JdbcClient），只對 java 有作用，其他語言載入也無妨
    codeql database analyze "/tmp/db/$lang" "codeql/$lang-queries" \
        --additional-packs=/work/ci/sast/models --model-packs=talkcal/java-models \
        --format=sarif-latest --output="$out/$lang.sarif" --threads=0 --quiet
done

# 每一筆結果配上它的規則的 security-severity（CVSS 分數，0～10），印成一行一筆：
#   分數<TAB>規則<TAB>檔案:行<TAB>訊息
# 規則可能在 driver（CLI 本身）或 extensions（查詢包）裡，兩邊都找
jq -r '
    .runs[]
    | ([.tool.driver, (.tool.extensions // [])[]] | map(.rules // [] | .[]) |
        map({key: .id, value: (.properties["security-severity"] // "0" | tonumber)}) | from_entries) as $sev
    | .results[]
    | [($sev[.ruleId] // 0),
       .ruleId,
       (.locations[0].physicalLocation | "\(.artifactLocation.uri):\(.region.startLine)"),
       (.message.text | gsub("\n"; " ") | gsub("\\|"; "\\|"))]  # | 會切斷下面摘要的 markdown 表格
    | @tsv
' "$out"/*.sarif | sort -rn > "$out/results.tsv"

# 摘要（GitHub 貼到結果頁）：全部列出，7.0 以上的才讓 CI 紅
{
    echo "## 原始碼安全分析（CodeQL）"
    echo
    if [ -s "$out/results.tsv" ]; then
        echo "| security-severity | 規則 | 位置 | 說明 |"
        echo "|---|---|---|---|"
        awk -F'\t' '{ printf "| %s%s | `%s` | `%s` | %s |\n", $1, ($1 >= 7 ? " ❌" : ""), $2, $3, $4 }' "$out/results.tsv"
    else
        echo "沒有任何結果。"
    fi
} > "$out/summary.md"
cat "$out/summary.md"

blocking=$(awk -F'\t' '$1 >= 7' "$out/results.tsv" | wc -l)
if [ "$blocking" -gt 0 ]; then
    echo "有 $blocking 個 security-severity 7.0 以上（HIGH、CRITICAL）的結果。" >&2
    exit 1
fi
