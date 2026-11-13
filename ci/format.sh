#!/usr/bin/env bash
# 後端 Java 的格式檢查（Spotless 的 spotlessCheck、spotlessApply）。
#
#   ci/format.sh            檢查
#   ci/format.sh apply      直接排好（等於 cd backend && ./gradlew spotlessApply）
#
# backend/build.gradle 還沒接 Spotless，現在跑會因為找不到 spotlessCheck 這個 task 而失敗；
# 格式化為什麼還沒做，見 ci/README.md「格式化為什麼還沒做」。
#
# 為什麼不放進 checks.sh：要跟 origin/master 比出「改過哪些檔案」就要有 git 歷史，
# 而 checks.sh 的容器只有某一個 commit 的檔案、沒有 .git。
# 前端的 Prettier、ESLint 檢查整個 web/，不需要歷史，所以留在 checks.sh。
# Error Prone 掛在 javac 上，checks.sh 編譯時就會跑到，也不在這裡。
#
# 在主機上跑，不進容器：Spotless 只讀原始碼、不必編譯，主機的 JDK（17 以上）就夠。
# 要先有 origin/master：GitHub 上 checkout 時 fetch-depth: 0，本機平常 git fetch 過就有。
#
# 注意：檢查的是工作目錄裡的檔案，不是某個 commit。pre-push 時有還沒 commit 的改動，結果可能跟推上去的不一樣。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)/backend"

if ! git rev-parse --verify --quiet origin/master >/dev/null; then
    echo "找不到 origin/master，不知道要跟誰比。先 git fetch origin master。" >&2
    exit 1
fi

if [ "${1:-check}" = apply ]; then
    exec ./gradlew --console=plain spotlessApply
fi

if ! ./gradlew --console=plain spotlessCheck; then
    echo >&2
    echo "Java 格式不對。自動排好：ci/format.sh apply（或 cd backend && ./gradlew spotlessApply），再 commit。" >&2
    exit 1
fi
