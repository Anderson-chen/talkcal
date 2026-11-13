#!/usr/bin/env bash
# 檢查這次新增、改動的 DB migration，會不會在新舊版本同時在線時壞掉。
#
#   ci/migrations.sh            跟 origin/master 比
#   ci/migrations.sh <base>     跟指定的 commit 比
#
# 兩道檢查：
#   1. <base> 裡已經有的 migration 不能改、刪、改名：Flyway 會比對跑過的檔案的 checksum，
#      改了舊檔，已經跑過的資料庫一啟動就驗證失敗，要部署到一半才發現。
#   2. 新增的 migration 給 squawk 看：直接 DROP、RENAME COLUMN、改型別、不帶 CONCURRENTLY 建索引這類寫法，
#      舊版還在線上時會壞掉或鎖住整張表。每條規則的理由和安全的替代做法見 https://squawkhq.com/docs/rules
# squawk 只看新增的檔案：已經跑過的改不了，掃出來的警告也修不掉，只會把新的問題淹沒。
#
# 真的要做危險的變更（例如刪掉已經沒人用的欄位），在那一句上面加
#   -- squawk-ignore ban-drop-column
# 並寫清楚為什麼現在刪是安全的（從哪個版本起就沒人讀寫它）。
#
# 不放進 checks.sh：那邊的容器沒有 .git，比不出這次改了哪些。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

base=${1:-origin/master}
dir=backend/src/main/resources/db/migration
# 版本釘死。npm 上發布過的版本號不能換內容；squawk 的執行檔也是 npm 套件（@squawk-cli/<平台>），安裝時不另外下載。
# 升級前看 changelog：新版常多規則，但只掃新增的檔案，舊的不受影響
squawk='squawk-cli@2.66.0'

if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
    echo "找不到要比對的 commit：$base（PR 上要 checkout 時 fetch-depth: 0）" >&2
    exit 2
fi

status=0

echo "── 已經跑過的 migration 不能改 ──"
# --no-renames：改名會變成「刪掉舊的、新增一支」，舊的那支照樣算被刪
changed=$(git diff --no-renames --name-only --diff-filter=MD "$base"...HEAD -- "$dir")
if [ -n "$changed" ]; then
    echo "這些 migration 在 $base 裡就有了，不能改也不能刪（要改資料庫，加一支新的 V 檔）："
    # shellcheck disable=SC2001  # 每一行前面加縮排；改用 ${//} 得在替換字串裡寫換行，反而難讀
    echo "$changed" | sed 's/^/  /'
    status=1
else
    echo "沒有改到舊的 migration。"
fi

echo "── 新增的 migration：squawk ──"
added=$(git diff --name-only --diff-filter=A "$base"...HEAD -- "$dir" | grep '\.sql$' || true)
if [ -z "$added" ]; then
    echo "沒有新增 migration。"
else
    # shellcheck disable=SC2001  # 同上
    echo "$added" | sed 's/^/  /'
    # 規則和 PostgreSQL 版本設定在 ci/squawk.toml
    # shellcheck disable=SC2086 # 檔名一行一個、沒有空白（Flyway 的命名規則），要拆成多個參數
    npx --yes "$squawk" --config ci/squawk.toml $added || status=1
fi

exit $status
