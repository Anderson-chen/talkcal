#!/usr/bin/env bash
# 打 release tag（#25）。先檢查、再在本機建 annotated tag，最後印出推上去的指令 —— 推之前還有一次機會看一眼。
#
#   cd/release.sh v0.2.0              # 打在 origin/master 最新的 commit
#   cd/release.sh v0.2.0 3f9c2ab      # 打在 master 上比較早的某個 commit（例如確認過的那一版）
#
# 流程和為什麼是手動，見 docs/release.md。
#
# 擋下來的情況（每一條都是打錯之後很難收回的）：
#   1. 名字不是 v<主>.<次>.<修>：cd/version.sh 不會把它當成版本
#   2. 這個 tag 已經存在（本機或 GitHub 上）：tag 推出去就不該移動，同一個名字指到兩個 commit 是最糟的情況
#   3. commit 不在 master 上：沒走過 PR 的東西不該變成 release
#   4. 那個 commit 的 CI 沒有綠燈
#   5. 版本號沒有比它之前最近的 tag 大：v0.3.0 之後不能再出 v0.2.9

set -euo pipefail
cd "$(dirname "$0")/.."

version="${1:?用法：cd/release.sh v<主>.<次>.<修> [master 上的 commit，預設 origin/master]}"
target="${2:-origin/master}"

fail() { echo "✗ $*" >&2; exit 1; }

# 1
[[ "$version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "版本號要是 v<主>.<次>.<修>（例如 v0.2.0），拿到的是 $version"

# 先只拉 master、不拉 tag：本機若有一個跟 GitHub 同名但指向不同 commit 的 tag，
# git fetch --tags 會直接報 would clobber 失敗，看不出是第 2 條擋的
git fetch --quiet origin master
sha="$(git rev-parse --verify --quiet "$target^{commit}")" || fail "找不到 $target"
echo "目標：$version → ${sha:0:7} $(git log -1 --format=%s "$sha")"

# 2
if git rev-parse --verify --quiet "refs/tags/$version" >/dev/null; then
  fail "本機已經有 $version（指著 $(git rev-parse --short "$version^{commit}")）"
fi
if [ -n "$(git ls-remote --tags origin "refs/tags/$version")" ]; then
  fail "GitHub 上已經有 $version"
fi

# 3
git merge-base --is-ancestor "$sha" origin/master || fail "${sha:0:7} 不在 origin/master 上"
echo "  ✓ 在 origin/master 上"

# 4 只看 push 事件：PR 的 run 測的是合併前的版本（跟 cd/triggers/watch.sh 同一個理由）
ci="$(gh run list --workflow ci.yml --commit "$sha" --event push --status success --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
[ -n "$ci" ] || fail "${sha:0:7} 沒有綠燈的 CI（gh run list --workflow ci.yml --commit $sha）"
echo "  ✓ CI 綠燈（run $ci）"

# 5 現在才拉 tag：第 5 條要知道之前打過哪些
git fetch --quiet --tags origin
previous="$(git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' "$sha" 2>/dev/null || true)"
if [ -n "$previous" ]; then
  # sort -V -C：已經依版本號排好（previous 在前）才回 0；相等的情況第 2 條已經擋掉了
  printf '%s\n%s\n' "$previous" "$version" | sort -V -C || fail "$version 沒有比上一個 $previous 大"
  echo "  ✓ 上一個是 $previous，之間 $(git rev-list --count "$previous..$sha") 個 commit"
else
  echo "  ✓ 第一個 release"
fi

# annotated（-a）而不是 lightweight：多記了誰、什麼時候打的，GitHub Release 和 git describe 都以它為準
git tag -a "$version" "$sha" -m "$version"
echo
echo "已在本機建好 $version。確認沒問題再推上去、開 GitHub Release："
echo "  git push origin $version"
echo "  gh release create $version --verify-tag --generate-notes"
echo "打錯了（還沒推）：git tag -d $version"
