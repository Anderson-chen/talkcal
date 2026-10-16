#!/usr/bin/env bash
# .github/ai-review.sh 的反例：每一種該擋的都造一個，確定會紅；正常填完的確定是綠。
# 本機跑：bash .github/ai-review.test.sh（workflow 每次檢查前也會先跑這支）

set -uo pipefail
cd "$(dirname "$0")"

HEAD=0123456789abcdef0123456789abcdef01234567
template=$(cat pull_request_template.md)

# 把範本當成人填好的樣子：全部勾、填 commit 和紀錄
filled=$(sed -e 's/^- \[ \]/- [x]/' \
  -e "s/^審查到的 commit：.*/審查到的 commit：${HEAD:0:7}/" \
  -e 's/^審查紀錄：.*/審查紀錄：重跑了反例，紅燈如預期/' <<<"$template")

pass=0 failed=0
# expect <綠或紅> <說明> <描述> <label> [head sha] [紅燈訊息裡要有的字]
expect() {
  local want=$1 name=$2 body=$3 labels=$4 head=${5:-$HEAD} needle=${6:-}
  local out code
  out=$(PR_BODY=$body PR_LABELS=$labels HEAD_SHA=$head bash ai-review.sh 2>&1)
  code=$?
  local got=綠; [ "$code" -ne 0 ] && got=紅
  if [ "$got" = "$want" ] && { [ -z "$needle" ] || grep -qF "$needle" <<<"$out"; }; then
    echo "ok   $want  $name"; pass=$((pass + 1))
  else
    echo "FAIL 預期$want 實際$got  $name"; sed 's/^/     /' <<<"$out"; failed=$((failed + 1))
  fi
}

expect 綠 "填完、看的是最新的 commit" "$filled" ai-generated
expect 綠 "SHA 大寫、用反引號包起來" "${filled/審查到的 commit：${HEAD:0:7}/審查到的 commit：\`0123456789ABCDEF\`}" ai-generated
expect 綠 "GitHub 存的 CRLF 描述" "$(sed 's/$/\r/' <<<"$filled")" ai-generated
expect 綠 "人寫的 PR 不用清單" "$template" human-written
expect 綠 "其他 label 不影響" "$filled" $'ai\nai-generated\nprocess'

expect 紅 "原封不動的範本（AI 剛開好的 PR）" "$template" ai-generated "" "還沒勾"
expect 紅 "少勾一項" "$(sed '0,/^- \[x\]/s//- [ ]/' <<<"$filled")" ai-generated "" "還沒勾"
expect 紅 "刪掉一項" "$(sed '0,/^- \[x\].*/{//d}' <<<"$filled")" ai-generated "" "應該有 6 項"
expect 紅 "審查完又 push" "$filled" ai-generated fedcba9876543210fedcba9876543210fedcba98 "又 push 了"
expect 紅 "commit 沒填（只留範本的註解）" "$(sed 's/^審查到的 commit：.*/審查到的 commit：<!-- 填這裡 -->/' <<<"$filled")" ai-generated "" "commit 沒填"
expect 紅 "commit 太短" "$(sed 's/^審查到的 commit：.*/審查到的 commit：0123/' <<<"$filled")" ai-generated "" "太短"
expect 紅 "審查紀錄沒填" "$(sed 's/^審查紀錄：.*/審查紀錄：  /' <<<"$filled")" ai-generated "" "審查紀錄沒填"
expect 紅 "清單整段被刪掉" "Closes #28" ai-generated "" "找不到"
expect 紅 "清單外面的勾不算" "- [x] a
- [x] b
- [x] c
- [x] d
- [x] e
- [x] f
審查到的 commit：${HEAD:0:7}
審查紀錄：看過了" ai-generated "" "找不到"
expect 紅 "沒標誰寫的" "$filled" "" "" "沒有標示"
expect 紅 "兩個 label 都標" "$template" $'ai-generated\nhuman-written' "" "只能留一個"

echo
echo "$pass 個通過，$failed 個失敗"
[ "$failed" -eq 0 ]
