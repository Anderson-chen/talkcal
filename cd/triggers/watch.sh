#!/usr/bin/env bash
# 本機（之後 EC2 也是）的 CD：自己去 GitHub 看 master 有沒有新的綠燈版本，有就部署。
# 由排程每幾分鐘跑一次，每次只看一輪就結束。手動跑一次也可以：
#
#   cd/triggers/watch.sh local
#
# 為什麼是機器自己來拉，而不是 GitHub 推過來：GitHub 不必有任何連進這台機器的權限
# （理由見 cd/README.md「為什麼是 pull」）。
#
# 這支腳本作用在「它自己所在的那個 clone」：會 checkout 別的 commit，所以要跑在專門部署用的 clone，
# 不要跑在開發用的那份（有沒 commit 的改動會直接拒絕，見下面）。
#
# 一輪做的事：
#   1. 問 GitHub：master 上最新一個 CI 綠燈的 commit 是哪個
#   2. 跟上次試過的比，一樣就結束（成功或失敗都不重試：失敗要人來看，不該每五分鐘重 build 一次）
#   3. checkout 那個 commit，跑「那個 commit 的」cd/apply.sh
#   4. 結果寫回 GitHub Deployments：GitHub 頁面上看得到哪個版本部署到哪、成功沒有

set -euo pipefail

# 整支包在函式裡：bash 會先讀完整個函式才開始跑。第 3 步 checkout 會換掉這個檔案本身，
# 不包的話 bash 可能讀到一半舊、一半新的內容
main() {
  local env_name="${1:?用法：cd/triggers/watch.sh <env>，env 是 cd/envs/ 底下的檔名（例如 local）}"
  local branch=master
  local workflow=ci.yml

  cd "$(dirname "$0")/../.."
  local git_dir
  git_dir="$(git rev-parse --absolute-git-dir)"
  local state="$git_dir/cd-$env_name.last"   # 上次試過的 sha。放 .git 裡：不會被 checkout 動到，也不會被 commit
  local log="$git_dir/cd-$env_name.log"
  local lock="$git_dir/cd-$env_name.lock"
  local env_file="cd/envs/$env_name.env"
  [ -f "$env_file" ] || { echo "沒有這個環境：$env_file" >&2; return 1; }
  # shellcheck source=/dev/null
  source "$env_file"   # 這裡只用到 SITE_URL；部署本身由 apply.sh 讀 checkout 之後的版本

  # 同時寫進 log：排程跑的時候沒有人看螢幕，事後要能查
  exec > >(tee -a "$log") 2>&1
  echo "══ $(date '+%F %T') watch $env_name"

  # 上一輪還在跑（build 映像檔可能要好幾分鐘）就這輪跳過。Git Bash 沒有 flock，用 mkdir：建資料夾是原子操作。
  # 超過 60 分鐘的鎖當成上一輪被砍掉留下的，清掉重來
  if [ -d "$lock" ] && [ -n "$(find "$lock" -maxdepth 0 -mmin +60)" ]; then
    echo "鎖超過 60 分鐘，當成殘留清掉：$lock"
    rmdir "$lock"
  fi
  if ! mkdir "$lock" 2>/dev/null; then
    echo "上一輪還在跑，這輪跳過"
    return 0
  fi
  # 路徑當下就展開：trap 在 main 結束後才觸發，那時 local 變數已經不在了
  trap "rmdir '$lock'" EXIT

  # 有沒 commit 的改動代表這多半是開發用的 clone，checkout 會把人家的工作蓋掉
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "這個 clone 有沒 commit 的改動，拒絕部署。watcher 要跑在專門部署用的 clone（見 cd/README.md）" >&2
    return 1
  fi

  # Docker 沒起來就整輪跳過、什麼都不記：開機後排程常常比 Docker Desktop 先跑，
  # 這時候部署一定失敗，但那是環境還沒好，不是這個版本壞了，不該記成「試過了」
  if ! docker info >/dev/null 2>&1; then
    echo "Docker 沒在跑，這輪跳過"
    return 0
  fi

  # 1. master 上最新一個 CI 綠燈的 commit。只看 push 事件：PR 的 run 跑的是合併前的版本
  local sha
  sha="$(gh run list --workflow "$workflow" --branch "$branch" --event push --status success \
           --limit 1 --json headSha --jq '.[0].headSha // empty')"
  if [ -z "$sha" ]; then
    echo "$branch 還沒有綠燈的 CI，沒東西可部署"
    return 0
  fi

  # 2. 跟上次試過的比
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
  # GitHub 上開好紀錄才記下來：前面連不上 GitHub 就整輪作廢，下一輪重來。
  # 從這裡開始，部署失敗也算試過，下一輪不會再試同一個
  echo "$sha" > "$state"

  # 3. checkout、部署。跑的是那個 commit 自己的 apply.sh 和 envs/：部署步驟跟著程式碼一起改版
  local ok=true
  git fetch --quiet origin "$sha" && git checkout --quiet --detach "$sha" || ok=false
  if $ok; then
    git log -1 --oneline
    cd/apply.sh "$env_name" || ok=false
  fi

  # 4. 回寫結果
  if $ok; then
    set_status "$repo" "$deployment_id" success "部署完成"
    echo "✅ ${sha:0:7} 部署到 $env_name"
  else
    # description 上限 140 字，放不下完整路徑
    set_status "$repo" "$deployment_id" failure "部署失敗，log 在部署機器的 .git/cd-$env_name.log"
    echo "❌ ${sha:0:7} 部署到 $env_name 失敗。修好後要重試：刪掉 $state 再跑一次" >&2
    return 1
  fi
}

# 在 GitHub 上開一筆部署紀錄，印出它的 id。
# required_contexts 給空陣列：GitHub 預設會再檢查一次這個 commit 的所有狀態，
# 但 CI 綠燈已經在第 1 步確認過了，而且 PR 才有的檢查（pr-issue）在 master 的 commit 上本來就不存在
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

main "$@"
exit
