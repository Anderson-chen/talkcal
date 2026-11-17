#!/usr/bin/env bash
# 從 stdin 讀 build context（tar），build 出指定名稱的映像檔。
#
#   git archive <commit> <檔案…> | ci/build-image.sh <映像檔名稱> <Dockerfile>
#
# CI_IMAGE_CACHE=gha：改用 buildx，映像檔每一層存進 GitHub Actions cache，下一台機器直接拿建好的層。
#   GitHub 每次都是新機器，本機 Docker 的快取是空的。scope 用映像檔名稱，每個映像檔的快取分開存。
#   mode=max：中間 stage 的層也存。下載 CodeQL 的約 700 MB 在 ci/sast/Dockerfile 的 fetch 那一段，
#   預設的 mode=min 只存最後一段，每次都要重新下載。
#   ignore-error=true：快取存不進去（額度滿了、被限流）只少了下次的快取，映像檔已經 build 好，不讓 CI 因此失敗。
#   前提：目前的 buildx builder 要能匯出快取（docker-container driver），環境變數要有
#   ACTIONS_RUNTIME_TOKEN、ACTIONS_RESULTS_URL。GitHub 上由 .github/actions/buildx-cache 準備。
# 沒有設定時用一般的 docker build，快取留在本機 Docker。

set -euo pipefail

image=$1
dockerfile=$2

if [ "${CI_IMAGE_CACHE:-}" = gha ]; then
    docker buildx build --load --quiet \
        --cache-from "type=gha,scope=$image" \
        --cache-to "type=gha,scope=$image,mode=max,ignore-error=true" \
        --tag "$image" --file "$dockerfile" - >/dev/null
else
    docker build --quiet --tag "$image" --file "$dockerfile" - >/dev/null
fi
