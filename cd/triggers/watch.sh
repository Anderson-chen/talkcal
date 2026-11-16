#!/usr/bin/env bash
# 本機的 CD（之後 EC2 也是）：向 GitHub 查詢 master 有沒有新的綠燈版本，有就部署。排程每幾分鐘執行一輪，執行完就結束。
#
#   cd/triggers/watch.sh local
#
# 由機器自己來拉，GitHub 不必有連進這台機器的權限（見 cd/README.md「為什麼是 pull」）。
# watcher 會 checkout 別的 commit，要放在專門部署用的 clone 執行，不要用開發用的那份。
#
# 排程執行的是 .git/cd-watch.sh 這份副本，不是這個檔案：這個檔案跟著 checkout 變，
# 部署到還沒有 watcher 的舊版本時就不見了，排程從此找不到要執行的檔案。副本只在部署成功後換成新版。
#
# 一輪：
#   1. 向 GitHub 查詢這個分支最新一個 CI 綠燈的 commit
#   2. 跟上次試過的一樣就結束。失敗了也不重試：要人來看，不該每幾分鐘重 build 一次
#   3. checkout 那個 commit，執行那個 commit 的 cd/apply.sh
#   4. 結果寫回 GitHub Deployments

set -euo pipefail

# 整支包在函式裡：bash 讀完整個函式才開始執行。第 3 步的 checkout 會換掉這個檔案，
# 不包的話 bash 可能讀到一半舊、一半新的內容
main() {
  local env_name="${1:?用法：cd/triggers/watch.sh <env>，env 是 cd/envs/ 底下的檔名（例如 local）}"
  local branch=master
  local workflow=ci.yml

  # 自己可能在 cd/triggers/（手動執行），也可能在 .git/（排程執行的副本），從 git 找 clone 的根目錄
  cd "$(dirname "$0")"
  local git_dir
  git_dir="$(git rev-parse --absolute-git-dir)"
  cd "$git_dir/.."
  # 狀態檔都放 .git 裡：checkout 動不到，也不會被 commit
  local state="$git_dir/cd-$env_name.last"
  local log="$git_dir/cd-$env_name.log"
  local lock="$git_dir/cd-$env_name.lock"
  local env_file="cd/envs/$env_name.env"
  [ -f "$env_file" ] || { echo "沒有這個環境：$env_file" >&2; return 1; }
  # shellcheck source=/dev/null
  source "$env_file"   # 這裡只用 SITE_URL；部署讀的是 checkout 之後那個版本的 env 檔

  # 排程執行時沒人看螢幕，全部寫進 log；手動執行時畫面也要有，用 tee。
  # 排程（conhost --headless）底下 stdout 不是有效的 handle，tee 一啟動就失敗，
  # 整支會在寫下第一行 log 前結束、排程器卻顯示成功。所以先讓 tee 試著執行一次，成功才用。
  # 不用 [ -t 1 ] 判斷：輸出接到管線和 headless 都回「不是 tty」，分不出來
  if : | tee 2>/dev/null; then
    exec > >(tee -a "$log") 2>&1
  else
    exec >>"$log" 2>&1
  fi
  echo "══ $(date '+%F %T') watch $env_name"

  # 上一輪還在執行就跳過這輪。Git Bash 沒有 flock，用 mkdir 當鎖：建資料夾是原子操作。
  # 超過 60 分鐘的鎖當成上一輪被終止後留下的（排程的執行上限是一小時，見 install-local.ps1）
  if [ -d "$lock" ] && [ -n "$(find "$lock" -maxdepth 0 -mmin +60)" ]; then
    echo "鎖超過 60 分鐘，當成殘留清掉：$lock"
    rmdir "$lock"
  fi
  if ! mkdir "$lock" 2>/dev/null; then
    echo "上一輪還在跑，這輪跳過"
    return 0
  fi
  # shellcheck disable=SC2064  # 路徑要現在展開：trap 在 main 結束後才觸發，那時 local 變數已經不在了
  trap "rmdir '$lock'" EXIT

  # 有沒 commit 的改動，多半是開發用的 clone，checkout 會蓋掉別人的工作
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "這個 clone 有沒 commit 的改動，拒絕部署。watcher 要跑在專門部署用的 clone（見 cd/README.md）" >&2
    return 1
  fi

  # Docker 沒啟動就整輪跳過、不記成試過：開機後排程常比 Docker Desktop 先執行，
  # 那是環境還沒好，不是這個版本壞了
  if ! docker info >/dev/null 2>&1; then
    echo "Docker 沒在跑，這輪跳過"
    return 0
  fi

  # 被 cd/rollback.sh 釘住就不部署：不然下一個綠燈的 commit 一出現，還沒修好的問題又被部署回來
  # （見 cd/rollback.md「退完之後，watcher 不會把新版部署回來」）。檔名要跟 rollback.sh 一致
  local pin="$git_dir/cd-$env_name.pin"
  if [ -f "$pin" ]; then
    echo "釘在 $(cat "$pin")，不自動部署。解除：cd/rollback.sh $env_name --unpin"
    return 0
  fi

  # 1. 只看 push 事件：PR 的 run 測的是合併前的版本
  local sha
  sha="$(gh run list --workflow "$workflow" --branch "$branch" --event push --status success \
           --limit 1 --json headSha --jq '.[0].headSha // empty')"
  if [ -z "$sha" ]; then
    echo "$branch 還沒有綠燈的 CI，沒東西可部署"
    return 0
  fi

  # 2.
  local last=""
  [ -f "$state" ] && last="$(cat "$state")"
  if [ "$sha" = "$last" ]; then
    echo "最新的綠燈版本 ${sha:0:7} 已經試過，不動"
    return 0
  fi
  echo "新版本：${last:0:7} → ${sha:0:7}"

  local repo
  repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
  local deployment_id
  deployment_id="$(create_deployment "$repo" "$sha" "$env_name")"
  set_status "$repo" "$deployment_id" in_progress "部署中"
  # GitHub 上的紀錄開好了才記成試過：前面連不上 GitHub 的話這輪作廢，下一輪重來。
  # 從這裡開始，部署失敗也算試過
  echo "$sha" > "$state"

  # 3. 執行那個 commit 自己的 apply.sh 和 envs/：部署步驟跟著程式碼一起改版
  local ok=true
  git fetch --quiet origin "$sha" && git checkout --quiet --detach "$sha" || ok=false
  if $ok; then
    git log -1 --oneline
    cd/apply.sh "$env_name" || ok=false
  fi

  # 4.
  if $ok; then
    set_status "$repo" "$deployment_id" success "部署完成"
    echo "✅ ${sha:0:7} 部署到 $env_name"
    # 副本換成這個版本的 watcher。這個版本沒有 watcher 的話留著現在的
    if [ -f cd/triggers/watch.sh ]; then
      cp cd/triggers/watch.sh "$git_dir/cd-watch.sh"
    fi
  else
    # GitHub 的 description 上限 140 字，放不下完整路徑
    set_status "$repo" "$deployment_id" failure "部署失敗，log 在部署機器的 .git/cd-$env_name.log"
    echo "❌ ${sha:0:7} 部署到 $env_name 失敗。修好後要重試：刪掉 $state 再跑一次" >&2
    return 1
  fi
}

# 在 GitHub 上建立一筆部署紀錄，印出紀錄的 id。
# required_contexts 給空陣列：不給的話 GitHub 會再檢查一次這個 commit 的所有狀態，
# 而 PR 才有的檢查（pr-issue）在 master 的 commit 上不存在，會被擋下來。CI 綠燈第 1 步已經確認過
create_deployment() {
  local repo="$1" sha="$2" env_name="$3"
  gh api "repos/$repo/deployments" --input - --jq .id <<EOF
{"ref": "$sha", "environment": "$env_name", "auto_merge": false, "required_contexts": [],
 "description": "cd/triggers/watch.sh"}
EOF
}

set_status() {
  local repo="$1" id="$2" state="$3" description="$4"
  gh api "repos/$repo/deployments/$id/statuses" \
    -f state="$state" -f description="$description" ${SITE_URL:+-f environment_url="$SITE_URL"} >/dev/null
}

# 呼叫和 exit 寫在同一行：bash 會整行讀完才執行。main 會覆寫 .git/cd-watch.sh（可能就是這個檔案），
# exit 放下一行的話，main 回來後 bash 會從原本的位置接著讀已經被換掉的檔案
main "$@"; exit $?
