#!/usr/bin/env bash
# 把這台機器上的 app、web 退回某個已經部署過的版本（#19），並釘住，watcher 不會自己再部署新的上來。
#
#   cd/rollback.sh local --list          機器上有哪些版本退得回去、現在跑哪個、有沒有釘住
#   cd/rollback.sh local 3f9c2ab         退到指定版本（版本號就是映像檔的 tag，見 docs/release.md）
#   cd/rollback.sh local --previous      退到 GitHub Deployments 上一個成功、機器上也還有映像檔的版本
#   cd/rollback.sh local --unpin         解除釘住，watcher 下一輪照常部署最新的綠燈版本
#
# 做法、為什麼這樣選、資料庫怎麼辦，見 docs/rollback.md。這裡只記幾個容易踩的地方：
#
# - 不 build、不 pull：只換成機器上已經有的映像檔。用 --build 的話 compose 會拿「目前 checkout 的程式碼」
#   build 出來、貼上舊版本的 tag —— 名字是舊的、內容是新的，等於沒退。沒有 --pull never 的話，
#   機器上沒有的版本 compose 會去 Docker Hub 找 talkcal/app，那不是我們的 namespace。
# - 換之前先確定 app、web 兩個映像檔都在，不在就整個不動：退一半（web 舊、app 新）比不退更難查。
# - 換了之後新版本起不來（--wait 逾時），就換回原本在跑的版本，不留一個掛掉的服務。
# - 跟 watcher 搶同一把鎖（.git/cd-<env>.lock）：watcher 正在部署時不退，退的途中 watcher 也不會插進來。

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

# 正在跑的 app 是哪個版本：容器用的映像檔 tag。沒在跑就是空的
running_version() {
  local id
  id="$(docker compose "${compose_args[@]}" ps -q "$1" 2>/dev/null || true)"
  [ -n "$id" ] || return 0
  docker inspect --format '{{.Config.Image}}' "$id" | sed 's/^.*://'
}

# app 和 web 兩個映像檔都在，這個版本才退得回去
has_images() {
  docker image inspect "talkcal/app:$1" "talkcal/web:$1" >/dev/null 2>&1
}

list_versions() {
  local current
  current="$(running_version app)"
  echo "機器上退得回去的版本（app、web 映像檔都在）："
  printf '  %-14s %-9s %s\n' 版本 commit 建立時間
  local v
  for v in $(docker images talkcal/app --format '{{.Tag}}' | sort -u); do
    has_images "$v" || continue
    local rev created mark=""
    rev="$(docker image inspect --format "{{index .Config.Labels \"$revision_label\"}}" "talkcal/app:$v")"
    rev="${rev:--}"   # 沒有 label：不是部署流程 build 的（例如手動 docker build）
    created="$(docker image inspect --format '{{.Created}}' "talkcal/app:$v" | cut -c1-16)"
    [ "$v" = "$current" ] && mark="  ← 現在跑的"
    printf '  %-14s %-9s %s%s\n' "$v" "${rev:0:7}" "$created" "$mark"
  done
  [ -f "$pin" ] && echo "釘住中：$(cat "$pin")（解除：cd/rollback.sh $env_name --unpin）"
  return 0
}

# GitHub Deployments 上，現在這版之前最近一次成功部署的 commit，再找機器上 label 是那個 commit 的映像檔。
# 不用映像檔的建立時間排「上一版」：build 有快取時，舊 commit 的映像檔可能比新的還「新」（示範時真的遇到）
previous_version() {
  local current_rev repo
  current_rev="$(docker image inspect --format "{{index .Config.Labels \"$revision_label\"}}" \
                   "talkcal/app:$(running_version app)" 2>/dev/null || true)"
  # 每一步失敗都要停：在 $(...) 裡 set -e 不算數，不擋的話 repo 是空的也會一路查下去（示範時真的發生過）。
  # tr -d '\r'：Windows 的 gh 輸出可能帶 CR，混進 id 會變成不合法的網址
  local deployments
  repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner | tr -d '\r')" && [ -n "$repo" ] || return 1
  deployments="$(gh api "repos/$repo/deployments?environment=$env_name&per_page=30" \
                   --jq '.[] | "\(.id) \(.sha)"' | tr -d '\r')" || return 1
  local id sha
  while read -r id sha; do
    [ -n "$id" ] || continue
    [ "$sha" = "$current_rev" ] && continue
    local state
    state="$(gh api "repos/$repo/deployments/$id/statuses?per_page=1" --jq '.[0].state // empty' | tr -d '\r')" || return 1
    [ "$state" = success ] || continue
    local v
    v="$(docker images talkcal/app --filter "label=$revision_label=$sha" --format '{{.Tag}}' | head -n 1)"
    if [ -n "$v" ] && has_images "$v"; then
      echo "$v"
      return 0
    fi
  done <<< "$deployments"
  return 1
}

# 把 web、app 換成某個版本，等 healthy。先換 web 再換 app，跟 apply.sh 相反：
# 中間那段是「舊前端配新後端」，新後端照規則要相容舊的呼叫方式；反過來「新前端配舊後端」可能打到舊版沒有的 API
switch_to() {
  local version="$1" svc
  for svc in web app; do
    local timeout="${WAIT_TIMEOUT:-$([ "$svc" = app ] && echo 300 || echo 60)}"
    echo "── $svc → $version（最多等 ${timeout} 秒）"
    APP_VERSION="$version" docker compose "${compose_args[@]}" \
      up -d --no-build --pull never --no-deps --wait --wait-timeout "$timeout" "$svc" || return 1
    # 確認真的換上了：compose 檔還沒有版本 tag（#25 之前）時 APP_VERSION 沒人讀，up 成功但什麼都沒換
    local now
    now="$(running_version "$svc")"
    if [ "$now" != "$version" ]; then
      echo "$svc 跑的是 $now，不是 $version：compose 檔的 image 沒有用 \${APP_VERSION}？（要有 #25）" >&2
      return 1
    fi
  done
}

record() {
  echo "$(date '+%F %T') $*" >> "$history"
}

# 在 GitHub Deployments 記一筆，頁面上看得到「這時候退回了哪一版」。
# 連不上 GitHub 也不算失敗：服務已經退好了，紀錄只是補充，本機的 history 檔有同樣的內容
record_github() {
  local version="$1" from="$2"
  local sha repo id
  sha="$(docker image inspect --format "{{index .Config.Labels \"$revision_label\"}}" "talkcal/app:$version")"
  if [ -z "$sha" ] || ! repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null | tr -d '\r')"; then
    echo "（沒寫 GitHub Deployments：映像檔沒有 commit label，或 gh 連不上）"
    return 0
  fi
  if id="$(gh api "repos/$repo/deployments" --input - --jq .id 2>/dev/null <<EOF
{"ref": "$sha", "environment": "$env_name", "auto_merge": false, "required_contexts": [],
 "description": "cd/rollback.sh $from → $version"}
EOF
)"; then
    id="$(printf '%s' "$id" | tr -d '\r')"
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
    if ! target="$(previous_version)"; then
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

# 1. 映像檔都在才開始。這一步之前什麼都還沒動
if ! has_images "$target"; then
  echo "機器上沒有 $target 的映像檔（talkcal/app:$target、talkcal/web:$target 要都在），什麼都沒動。" >&2
  echo "要部署機器上沒有的舊版本，見 docs/rollback.md「映像檔不在了」。" >&2
  list_versions >&2
  exit 1
fi

from="$(running_version app)"
if [ "$target" = "$from" ] && [ "$(running_version web)" = "$target" ]; then
  echo "現在跑的就是 $target，不用退"
  exit 0
fi

# 2. 拿 watcher 的鎖
if ! mkdir "$lock" 2>/dev/null; then
  echo "watcher 正在部署（$lock 存在），等它跑完再退。確定沒有 watcher 在跑的話刪掉這個資料夾" >&2
  exit 1
fi
trap 'rmdir "$lock"' EXIT

# 3. 先釘住再換：換的途中 watcher 就算拿得到鎖（例如鎖被當成殘留清掉）也不會部署
had_pin=""
[ -f "$pin" ] && had_pin="$(cat "$pin")"
echo "$target $(date '+%F %T') 從 $from 退回" > "$pin"
if [ ! -f "$git_dir/cd-watch.sh" ]; then
  echo "注意：這個 clone 沒有 watcher（$git_dir/cd-watch.sh）。釘住只對同一個 clone 的 watcher 有效，"
  echo "      watcher 在部署用的 clone 的話，rollback 要在那裡跑（見 docs/rollback.md）"
fi

echo "══ rollback $env_name：${from:-（沒在跑）} → $target"
if switch_to "$target"; then
  record "rollback $from -> $target"
  echo "✅ 退回 $target。watcher 已釘住，修好之後：cd/rollback.sh $env_name --unpin"
  record_github "$target" "$from"
  exit 0
fi

# 4. 新換上的起不來：換回原本的版本，不留一個掛掉的服務
echo "❌ $target 起不來，換回原本的 $from" >&2
if [ -n "$from" ] && switch_to "$from"; then
  echo "已換回 $from，服務照舊。$target 為什麼起不來：docker compose logs app" >&2
else
  echo "換回 $from 也失敗，服務現在可能是停的。手動部署：cd/apply.sh $env_name" >&2
fi
if [ -n "$had_pin" ]; then echo "$had_pin" > "$pin"; else rm -f "$pin"; fi
record "rollback $from -> $target FAILED, back to $from"
exit 1
