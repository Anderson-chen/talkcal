#!/usr/bin/env bash
# 印出目前 checkout 的版本號（#25）。映像檔的 tag、label 和 app 的 /actuator/info 都用它，規則只寫在這裡。
#
#   cd/version.sh   → v0.2.0 / 3f9c2ab / 3f9c2ab-dirty
#
# 只印版本號；完整的 commit 由呼叫的一方直接 git rev-parse HEAD。
#
# 規則（為什麼這樣選見 docs/release.md）：
#   1. HEAD 上有 release tag（v<主>.<次>.<修>）→ 那個 tag
#   2. 沒有 → commit 的前 7 碼（跟 GitHub 頁面、watcher 的 log 同樣長度）
#   3. 有沒 commit 的改動或新檔案 → 後面加 -dirty：映像檔裡的東西不等於那個 commit，不能假裝是
#
# 刻意不用 git describe 預設的 v0.2.0-3-g3f9c2ab：「離 tag 幾個 commit」要有完整歷史才算得出來，
# EC2 是 --depth 1 的淺 clone，同一個 commit 在本機和 EC2 會算出不同的版本號。這裡只看「指著 HEAD 的 tag」，淺 clone 也一樣。

set -euo pipefail
cd "$(dirname "$0")/.."

commit="$(git rev-parse HEAD)"

# 只認 v數字.數字.數字：其他名字的 tag（例如實驗用的）不會被當成版本。
# 同一個 commit 有好幾個 release tag 時取最新的（--sort 依版本號排）
tag="$(git tag --points-at HEAD --sort=-version:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n 1 || true)"
version="${tag:-${commit:0:7}}"

# 沒加進 git 的新檔案也算：放在 backend/src 底下就會被 build 進映像檔。
# 被 .gitignore 的（deploy/.env、build/、node_modules）git status 本來就不列，不會誤判
if [ -n "$(git status --porcelain)" ]; then
  version="$version-dirty"
fi

echo "$version"
