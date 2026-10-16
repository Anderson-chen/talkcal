#!/usr/bin/env bash
# AI 改動的人工審查有沒有做完（.github/workflows/ai-review.yml 呼叫，規則和理由見 docs/ai-review.md）。
#
# 輸入都從環境變數來，方便本機用假資料重跑（.github/ai-review.test.sh）：
#   PR_BODY    PR 描述
#   PR_LABELS  PR 的 label，一行一個
#   HEAD_SHA   PR 最新 commit 的完整 SHA
#
# 沒過就 exit 1，每個問題印一行 ::error::（GitHub 會把它貼在 PR 的檢查結果上）。

set -uo pipefail

# 清單有幾項。拿掉一項也能過的話，「不想勾的就刪掉」就成了繞過的路
EXPECTED_ITEMS=6

errors=0
fail() { echo "::error::$1"; errors=$((errors + 1)); }

labels=$(printf '%s\n' "${PR_LABELS:-}")
has_label() { grep -qxF "$1" <<<"$labels"; }

# 一定要標示是誰寫的：不標就當作沒有發生過 AI 改動，清單就整個被跳過了
if has_label ai-generated && has_label human-written; then
  fail "ai-generated 和 human-written 只能留一個。"
  exit 1
fi
if has_label human-written; then
  echo "label human-written：這個 PR 是人寫的，不需要 AI 改動審查清單。"
  exit 0
fi
if ! has_label ai-generated; then
  fail "PR 沒有標示誰寫的：加 label ai-generated（AI 寫的，要過下面的清單）或 human-written（人寫的）。"
  exit 1
fi

# GitHub 存的描述是 CRLF
body=$(printf '%s' "${PR_BODY:-}" | tr -d '\r')

# 只看標記之間：描述其他地方的勾選框（例如「改了什麼」裡的待辦）不算
section=$(awk '/<!-- ai-review:start -->/{on=1; next} /<!-- ai-review:end -->/{on=0} on' <<<"$body")
if [ -z "$section" ]; then
  fail "找不到 AI 改動審查清單（<!-- ai-review:start --> 到 <!-- ai-review:end -->）。從 .github/pull_request_template.md 把那段貼回描述。"
  exit 1
fi

# 範本裡的說明是 HTML 註解，可能跨行；拿掉之後才看得出欄位到底有沒有填
section=$(perl -0pe 's/<!--.*?-->//gs' <<<"$section")

unchecked=$(grep -E '^\s*[-*] \[ \]' <<<"$section" || true)
checked=$(grep -cE '^\s*[-*] \[[xX]\]' <<<"$section" || true)
if [ -n "$unchecked" ]; then
  while IFS= read -r line; do
    fail "還沒勾：${line#*\] }"
  done <<<"$unchecked"
elif [ "$checked" -lt "$EXPECTED_ITEMS" ]; then
  fail "清單只有 $checked 項，應該有 $EXPECTED_ITEMS 項：不適用的項目也要勾，在審查紀錄裡寫為什麼不適用，不要刪掉。"
fi

# 看過的是哪一版：之後再 push，看過的 diff 就不是要合併的 diff 了
reviewed=$(sed -nE 's/^審查到的 commit(:|：)[[:space:]]*`?([0-9a-fA-F]+)`?.*/\2/p' <<<"$section" | head -n1)
if [ -z "$reviewed" ]; then
  fail "審查到的 commit 沒填：貼上看過的那個 commit 的 SHA（至少 7 碼）。"
elif [ "${#reviewed}" -lt 7 ]; then
  fail "審查到的 commit「$reviewed」太短，至少 7 碼。"
else
  reviewed_lc=$(tr 'A-F' 'a-f' <<<"$reviewed")
  head_lc=$(tr 'A-F' 'a-f' <<<"${HEAD_SHA:-}")
  if [ "${head_lc#"$reviewed_lc"}" = "$head_lc" ]; then
    fail "審查到的是 $reviewed，PR 最新的是 ${HEAD_SHA:0:7}：看過之後又 push 了，新的 diff 要重看一次。"
  fi
fi

# 看過 diff 的證據：只勾格子看不出看了什麼
record=$(sed -nE 's/^審查紀錄(:|：)[[:space:]]*(.*)$/\2/p' <<<"$section" | head -n1)
if [ -z "${record// /}" ]; then
  fail "審查紀錄沒填：寫看了什麼、發現什麼、怎麼處理（沒發現問題也寫，例如「重跑了反例，紅燈如預期」）。"
fi

if [ "$errors" -gt 0 ]; then
  exit 1
fi
echo "AI 改動審查完成：$checked 項都勾了，看過的是 $reviewed（PR 最新的 commit）。"
echo "審查紀錄：$record"
