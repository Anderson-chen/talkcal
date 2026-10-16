#!/usr/bin/env bash
# 檢查這次新增、改動的 DB migration 會不會讓「新舊版本同時在線」的時候壞掉（#18）。
#
#   ci/migrations.sh                 跟 origin/master 比（PR、本機用）
#   ci/migrations.sh <base>          跟指定的 commit 比（push 到 master 時用推之前那個 commit）
#
# 兩道檢查：
#   1. 已經在 <base> 裡的 migration 不能改、不能刪、不能改名。
#      Flyway 會比對每支跑過的檔案的 checksum，改了舊檔，已經跑過的資料庫一啟動就驗證失敗 ——
#      也就是部署到一半才發現。在這裡擋，PR 上就看得到。
#   2. 新增的 migration 給 squawk 看：直接 DROP / RENAME COLUMN、改型別、不帶 CONCURRENTLY 建索引、
#      ADD CONSTRAINT 沒有 NOT VALID、ALTER TABLE 前沒設 lock_timeout…… 這些在舊版還在線上時會壞掉或鎖住整張表。
#      每一條規則的理由、安全的替代做法，見 docs/database/zero-downtime-schema.md。
#
# 只看新增的檔案，不回頭掃 V1～V11：那些已經跑過、改不了（第 1 道就是在擋這件事），
# 掃出來的警告也修不掉，只會讓真正新的問題淹沒在舊的裡面。
#
# 真的要做危險的變更（例如 contract 那一步刪掉已經沒人用的欄位）：在那一句上面加
#   -- squawk-ignore ban-drop-column
# 並在註解寫清楚「為什麼現在刪是安全的」（哪個版本起就沒人讀寫它了）。ignore 本身在 PR 的 diff 裡看得到，就是審查的重點。
#
# 不放進 checks.sh：那邊的容器只有某一個 commit 的檔案、沒有 .git，比不出「這次改了哪些」（同 secrets.sh）。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

base=${1:-origin/master}
dir=backend/src/main/resources/db/migration
# 版本釘死。npm 上同一個版本號發布後不能被換掉；squawk 的執行檔也是 npm 套件（@squawk-cli/<平台>），
# 安裝時不另外下載東西。升級時看 changelog：新版常常會多規則，舊的 migration 不受影響（只掃新增的）
squawk=squawk-cli@2.66.0

if ! git rev-parse --verify --quiet "$base^{commit}" >/dev/null; then
    echo "找不到要比對的 commit：$base（PR 上要 checkout 時 fetch-depth: 0）" >&2
    exit 2
fi

status=0

echo "── 已經跑過的 migration 不能改 ──"
# --no-renames：改名也當成「刪掉舊的 + 新增」，舊的那支照樣算被刪
changed=$(git diff --no-renames --name-only --diff-filter=MD "$base"...HEAD -- "$dir")
if [ -n "$changed" ]; then
    echo "這些 migration 在 $base 裡就有了，不能改也不能刪（要改資料庫，加一支新的 V 檔）："
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
    echo "$added" | sed 's/^/  /'
    # 規則、PostgreSQL 版本、「每支 migration 都在交易裡跑」（Flyway 的預設）寫在 ci/squawk.toml
    # shellcheck disable=SC2086 # 檔名一行一個、沒有空白（Flyway 的命名規則），要拆成多個參數
    npx --yes "$squawk" --config ci/squawk.toml $added || status=1
fi

exit $status
