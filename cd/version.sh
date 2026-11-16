#!/usr/bin/env bash
# 印出目前 checkout 的版本號，映像檔的 tag 和 label 都用這個版本號。為什麼這樣定見 cd/release.md。
#
#   cd/version.sh   → v0.2.0 / 3f9c2ab / 3f9c2ab-dirty
#
#   1. HEAD 上有 release tag（v<主>.<次>.<修>）就用那個 tag
#   2. 沒有就用 commit 的前 7 碼
#   3. 有沒 commit 的改動或新檔案，後面加 -dirty：映像檔的內容不等於那個 commit

set -euo pipefail
cd "$(dirname "$0")/.."

commit="$(git rev-parse HEAD)"

# 只接受 v<主>.<次>.<修>，其他名字的 tag 不當成版本。同一個 commit 有好幾個時取版本號最大的
tag="$(git tag --points-at HEAD --sort=-version:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n 1 || true)"
version="${tag:-${commit:0:7}}"

# 沒加進 git 的新檔案也算，例如放在 backend/src 底下就會被 build 進映像檔。
# 被 .gitignore 的檔案（deploy/.env、build/）git status 本來就不列
if [ -n "$(git status --porcelain)" ]; then
  version="$version-dirty"
fi

echo "$version"
