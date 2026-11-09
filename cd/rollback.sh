#!/usr/bin/env bash
# 把這台機器上的 app、web 退回某個已經部署過的版本（#19），並釘住，watcher 不會自己再部署新的上來。
#
#   cd/rollback.sh local --list          機器上有哪些版本退得回去、現在跑哪個、有沒有釘住
#   cd/rollback.sh local 3f9c2ab         退到指定版本（版本號就是映像檔的 tag，見 docs/release.md）
#   cd/rollback.sh local --previous      退到 GitHub Deployments 上一個成功、機器上也還有映像檔的版本
#   cd/rollback.sh local --unpin         解除釘住，watcher 下一輪照常部署最新的綠燈版本
#
# 為什麼 --no-build --pull never、為什麼先換 web、起不來為什麼換回：docs/rollback.md「選型」。
# 鎖和釘住的檔名（.git/cd-<env>.lock、.pin）跟 cd/triggers/watch.sh 是同一組，改名要兩邊一起改。

set -euo pipefail
cd "$(dirname "$0")/.."

usage="用法：cd/rollback.sh <env> <版本> | --previous | --list | --unpin"
env_name="${1:?$usage}"
target="${2:?$usage}"
env_file="cd/envs/$env_name.env"
[ -f "$env_file" ] || { echo "沒有這個環境：$env_file" >&2; exit 1; }
# shellcheck source=/dev/null
source "$env_file"
: "${COMPOSE_FILES:?$env_file 要設定 COMPOSE_FILES}"

compose_args=()
for f in $COMPOSE_FILES; do compose_args+=(-f "$f"); done

# 釘住、鎖、紀錄都放在 watcher 用的同一個地方：.git 裡，不會被 checkout 動到、也不會被 commit
git_dir="$(git rev-parse --absolute-git-dir)"
pin="$git_dir/cd-$env_name.pin"
lock="$git_dir/cd-$env_name.lock"
history="$git_dir/cd-$env_name.history"

revision_label=org.opencontainers.image.revision

# Windows 的 gh 輸出可能帶 CR，混進 id 會變成不合法的網址：所有 gh 呼叫都經過這裡
gh_() {
  gh "$@" | tr -d '\r'
}

repo=""
repo_name() {
  [ -n "$repo" ] || repo="$(gh_ repo view --json nameWithOwner --jq .nameWithOwner)"
  [ -n "$repo" ]
}

# 正在跑的那個服務是哪個版本：容器用的映像檔 tag。沒在跑就是空的
running_version() {
  local id
  id="$(docker compose "${compose_args[@]}" ps -q "$1" 2>/dev/null || true)"
  [ -n "$id" ] || return 0
  docker inspect --format '{{.Config.Image}}' "$id" | sed 's/^.*://'
}

# app 映像檔 label 上的 commit，只認 40 碼：沒有 label、或沒經過 apply.sh 直接 build 的
# （Dockerfile 的預設值 unknown）都當成沒有 —— 不知道裡面是哪個 commit，退過去等於賭
revision_of() {
  local rev
  rev="$(docker image inspect --format "{{index .Config.Labels \"$revision_label\"}}" "talkcal/app:$1" 2>/dev/null || true)"
  [[ "$rev" =~ ^[0-9a-f]{40}$ ]] && echo "$rev"
  return 0
}

# -dirty：從有沒 commit 改動的 clone build 的，label 寫的 commit 跟內容不一樣（規則在 cd/version.sh）
is_dirty() {
  [[ "$1" == *-dirty ]]
}

# 退得回去：app 帶著 commit（上面那條規則），web 也在。印出 commit
deployable() {
  local rev
  rev="$(revision_of "$1")"
  [ -n "$rev" ] && docker image inspect "talkcal/web:$1" >/dev/null 2>&1 && echo "$rev"
}

list_versions() {
  local current v rev mark
  current="$(running_version app)"
  echo "機器上退得回去的版本（app 帶 commit label、web 也在）："
  for v in $(docker images talkcal/app --format '{{.Tag}}' | sort -u); do
    rev="$(deployable "$v")" || continue
    mark=""
    is_dirty "$v" && mark="  （-dirty：內容不等於這個 commit）"
    [ "$v" = "$current" ] && mark="$mark  ← 現在跑的"
    echo "  $v  ${rev:0:7}$mark"
  done
  [ -f "$pin" ] && echo "釘住中：$(cat "$pin")（解除：cd/rollback.sh $env_name --unpin）"
  return 0
}

# GitHub Deployments 上，現在這版之前最近一次成功部署的 commit，再找機器上 label 是那個 commit 的映像檔。
# 不用映像檔的建立時間排「上一版」：build 有快取時，舊 commit 的映像檔可能比新的還「新」（示範時真的遇到）。
# 結果直接寫進 target，不用 $(...) 接：在子 shell 裡跑的話，查到的 repo 帶不回來，記錄時又要再查一次
previous_version() {
  local current_rev deployments id sha state v
  current_rev="$(revision_of "$(running_version app)")"
  # 每一步失敗都要停：在 if 裡呼叫時 set -e 不算數，不擋的話 repo 是空的也會一路查下去（示範時真的發生過）
  repo_name || return 1
  deployments="$(gh_ api "repos/$repo/deployments?environment=$env_name&per_page=30" \
                   --jq '.[] | "\(.id) \(.sha)"')" || return 1
  while read -r id sha; do
    [ -n "$id" ] && [ "$sha" != "$current_rev" ] || continue
    state="$(gh_ api "repos/$repo/deployments/$id/statuses?per_page=1" --jq '.[0].state // empty')" || return 1
    [ "$state" = success ] || continue
    # 同一個 commit 可能有好幾個映像檔（例如 3b28b34 和打了 release tag 之後的 v0.1.0）：
    # 先挑 release tag（LC_ALL=C 下 v 排在數字、a-f 後面，sort -r 讓 v* 在前），-dirty 不挑
    for v in $(docker images talkcal/app --filter "label=$revision_label=$sha" --format '{{.Tag}}' | LC_ALL=C sort -r); do
      is_dirty "$v" && continue
      if deployable "$v" >/dev/null; then
        target="$v"
        return 0
      fi
    done
  done <<< "$deployments"
  return 1
}

# 把 web、app 各換成指定的版本，等 healthy。先換 web 再換 app，跟 apply.sh 相反（理由見 docs/rollback.md）。
# 兩個版本分開給：換回原本的時候，web 和 app 原本不一定是同一版（例如只用 apply.sh 換過 web）
switch_to() {
  local web_version="$1" app_version="$2" svc version timeout now
  for svc in web app; do
    version="$web_version"
    [ "$svc" = app ] && version="$app_version"
    timeout=60
    [ "$svc" = app ] && timeout=300
    timeout="${WAIT_TIMEOUT:-$timeout}"
    echo "── $svc → $version（最多等 ${timeout} 秒）"
    APP_VERSION="$version" docker compose "${compose_args[@]}" \
      up -d --no-build --pull never --no-deps --wait --wait-timeout "$timeout" "$svc" || return 1
    # 確認真的換上了：compose 檔的 image 沒有用 ${APP_VERSION} 的話，up 成功但什麼都沒換
    now="$(running_version "$svc")"
    if [ "$now" != "$version" ]; then
      echo "$svc 跑的是 $now，不是 $version：compose 檔的 image 有沒有用 \${APP_VERSION}？" >&2
      return 1
    fi
  done
}

record() {
  echo "$(date '+%F %T') $*" >> "$history"
}

# 在 GitHub Deployments 記一筆，頁面上看得到「這時候退回了哪一版」。
# 跟 watch.sh 的 create_deployment、set_status 是同一種紀錄：watch.sh 跑的是 .git 裡的副本，不能 source 共用檔，所以各寫一份。
# 連不上 GitHub 也不算失敗：服務已經退好了，本機的 history 檔有同樣的內容
record_github() {
  local version="$1" from="$2" sha id
  sha="$(revision_of "$version")"
  if ! repo_name 2>/dev/null; then
    echo "（沒寫 GitHub Deployments：gh 連不上）"
    return 0
  fi
  if id="$(gh_ api "repos/$repo/deployments" --input - --jq .id 2>/dev/null <<EOF
{"ref": "$sha", "environment": "$env_name", "auto_merge": false, "required_contexts": [],
 "description": "cd/rollback.sh $from → $version"}
EOF
)"; then
    gh api "repos/$repo/deployments/$id/statuses" -f state=success \
      -f description="rollback：$from → $version" ${SITE_URL:+-f environment_url="$SITE_URL"} >/dev/null \
      && echo "已寫進 GitHub Deployments（$env_name）"
  else
    echo "（沒寫 GitHub Deployments：建立部署紀錄失敗）"
  fi
}

case "$target" in
  --list)
    list_versions
    exit 0
    ;;
  --unpin)
    if [ -f "$pin" ]; then
      echo "解除釘住：$(cat "$pin")"
      rm "$pin"
      record "unpin"
      echo "watcher 下一輪會部署 master 最新的綠燈版本（同一個 commit 已經試過的不會再試）"
    else
      echo "沒有釘住"
    fi
    exit 0
    ;;
  --previous)
    if ! previous_version; then
      echo "GitHub Deployments 上找不到「之前成功、機器上也還有映像檔」的版本。指定版本退：" >&2
      list_versions >&2
      exit 1
    fi
    echo "上一個成功部署的版本：$target"
    ;;
  -*)
    echo "$usage" >&2
    exit 1
    ;;
esac

# 版本號就是 docker tag：只允許 tag 合法的字元，也擋掉被當成選項或路徑的輸入
if ! [[ "$target" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  echo "不是合法的版本號：$target" >&2
  exit 1
fi

# 1. 退得回去才開始。這一步之前什麼都還沒動
if ! target_rev="$(deployable "$target")"; then
  echo "$target 退不回去（talkcal/app:$target 要帶 commit label、talkcal/web:$target 也要在），什麼都沒動。" >&2
  echo "要部署機器上沒有的舊版本，見 docs/rollback.md「映像檔不在了」。" >&2
  list_versions >&2
  exit 1
fi
if is_dirty "$target"; then
  echo "注意：$target 是 -dirty 的版本，內容不等於 ${target_rev:0:7} 這個 commit"
fi

from="$(running_version app)"
from_web="$(running_version web)"
if [ "$target" = "$from" ] && [ "$from_web" = "$target" ]; then
  echo "現在跑的就是 $target，不用退"
  exit 0
fi
shown_from="${from:-（沒在跑）}"   # 只用在訊息和紀錄；判斷能不能換回看 from 本身

# 退的是映像檔，compose 設定用的是目前 checkout 的：兩版之間 compose 檔改過的話，舊映像檔會配上新的設定
# （例如改名的環境變數）。從 checkout 拿舊版的 compose 檔不行：相對路徑、.env 的位置都綁在這個 checkout 上，所以只提醒
# shellcheck disable=SC2086
if ! git cat-file -e "$target_rev^{commit}" 2>/dev/null; then
  echo "注意：這個 clone 沒有 ${target_rev:0:7} 這個 commit，比對不了兩版的 compose 設定"
elif ! git diff --quiet "$target_rev" HEAD -- $COMPOSE_FILES; then
  echo "注意：${target_rev:0:7} 之後 compose 設定改過，舊映像檔會配上目前的設定（見 docs/rollback.md「compose 設定」）："
  git diff --stat "$target_rev" HEAD -- $COMPOSE_FILES
fi

# 2. 拿 watcher 的鎖：換的途中 watcher 不會插進來
# 超過 60 分鐘的鎖當成上一輪被砍掉留下的，跟 watch.sh 同一條規則：出事要退版時不該被殘留的鎖擋住
if [ -d "$lock" ] && [ -n "$(find "$lock" -maxdepth 0 -mmin +60)" ]; then
  echo "鎖超過 60 分鐘，當成殘留清掉：$lock"
  rmdir "$lock"
fi
if ! mkdir "$lock" 2>/dev/null; then
  echo "watcher 正在部署（$lock 存在），等它跑完再退。確定沒有 watcher 在跑的話刪掉這個資料夾" >&2
  exit 1
fi
trap 'rmdir "$lock"' EXIT

echo "══ rollback $env_name：$shown_from → $target"
if switch_to "$target" "$target"; then
  # 3. 換好了才釘住：鎖還拿著，watcher 不會在這之前部署；失敗的話就不用還原釘住
  echo "$target $(date '+%F %T') 從 $shown_from 退回" > "$pin"
  record "rollback $shown_from -> $target"
  echo "✅ 退回 $target。watcher 已釘住，修好之後：cd/rollback.sh $env_name --unpin"
  if [ ! -f "$git_dir/cd-watch.sh" ]; then
    echo "注意：這個 clone 沒有 watcher（$git_dir/cd-watch.sh）。釘住只對同一個 clone 的 watcher 有效，"
    echo "      watcher 在部署用的 clone 的話，rollback 要在那裡跑（見 docs/rollback.md）"
  fi
  record_github "$target" "$shown_from"
  exit 0
fi

# 4. 換上去起不來：換回原本的版本，不留一個掛掉的服務
echo "❌ $target 起不來，換回原本的 $shown_from" >&2
if [ -n "$from" ] && [ -n "$from_web" ] && switch_to "$from_web" "$from"; then
  echo "已換回 $from（web $from_web），服務照舊。$target 為什麼起不來：docker compose logs app" >&2
else
  echo "換不回原本的版本（$shown_from），服務現在可能是停的。手動部署：cd/apply.sh $env_name" >&2
fi
record "rollback $shown_from -> $target FAILED"
exit 1
