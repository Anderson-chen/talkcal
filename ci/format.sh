#!/usr/bin/env bash
# 後端 Java 的格式檢查，執行 Spotless 的 spotlessCheck、spotlessApply。
#
#   ci/format.sh            檢查
#   ci/format.sh apply      直接排好
#
# backend/build.gradle 還沒接 Spotless，現在執行會因為找不到 spotlessCheck 而失敗；
# 格式化為什麼還沒做，見 ci/README.md「格式化為什麼還沒做」。
#
# 在主機上執行、不進 checks.sh 的容器：要跟 origin/master 比出改過的檔案就要有 .git，容器裡沒有。
# Spotless 不必編譯，主機的 JDK（17 以上）就夠。
# 檢查的是工作目錄裡的檔案，不是某個 commit：有還沒 commit 的改動時，結果可能跟推上去的不一樣。

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
