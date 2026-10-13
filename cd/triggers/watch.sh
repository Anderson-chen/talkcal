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
# 排程跑的不是這個檔案，是 .git/cd-watch.sh 這份副本（install-local.ps1 放的）：
# 這個檔案會跟著 checkout 變，部署到一個還沒有 watcher 的舊版本時，它就不見了，之後排程叫不到任何東西。
# 副本只在部署成功後才換成新版，watcher 跟著綠燈版本升級，但不會被自己部署的東西刪掉。
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

  # 找 clone 的根目錄。自己可能在 cd/triggers/（手動跑），也可能在 .git/（排程跑的副本）
  cd "$(dirname "$0")"
  local git_dir
  git_dir="$(git rev-parse --absolute-git-dir)"
  cd "$git_dir/.."
  local state="$git_dir/cd-$env_name.last"   # 上次試過的 sha。放 .git 裡：不會被 checkout 動到，也不會被 commit
  local log="$git_dir/cd-$env_name.log"
  local lock="$git_dir/cd-$env_name.lock"
  local env_file="cd/envs/$env_name.env"
  [ -f "$env_file" ] || { echo "沒有這個環境：$env_file" >&2; return 1; }
  # shellcheck source=/dev/null
  source "$env_file"   # 這裡只用到 SITE_URL；部署本身由 apply.sh 讀 checkout 之後的版本

  # 寫進 log：排程跑的時候沒有人看螢幕，事後要能查。
  # 手動跑時畫面和 log 都要有，用 tee；排程（conhost --headless）底下 stdout 不是有效的 handle，
  # tee 一啟動就失敗（Bad file descriptor），整支在寫下第一行 log 前就結束、排程器還顯示成功。
  # 所以先讓 tee 對 stdout 空跑一次，成功才 tee，失敗就只寫 log。
  # 不用 [ -t 1 ]：被管線接走（| tail）和 headless 它都回「不是 tty」，分不出來
  if : | tee 2>/dev/null; then
    exec > >(tee -a "$log") 2>&1
  else
    exec >>"$log" 2>&1
  fi
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
    # 排程跑的副本換成這個版本的 watcher。這個版本沒有 watcher（部署了舊版）就留著原本的
    if [ -f cd/triggers/watch.sh ]; then
      cp cd/triggers/watch.sh "$git_dir/cd-watch.sh"
    fi
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

# 呼叫和 exit 寫在同一行：bash 讀到這一行就整行讀完才執行。main 裡會覆寫 .git/cd-watch.sh（可能就是這個檔案），
# exit 放下一行的話，main 回來後 bash 會從原本的位置接著讀已經被換掉的檔案
main "$@"; exit $?
