#!/usr/bin/env bash
# 在「這台機器」上把服務換成目前 checkout 的版本，等它們 healthy 才結束。
# 本機和雲端跑的都是這一支；兩邊的差異寫在 cd/envs/<env>.env，不寫在這裡。
#
#   cd/apply.sh local                 部署 app、web（預設，不含模型）
#   cd/apply.sh local web             只部署前端
#   cd/apply.sh local llm-chat        只換模型（很貴，刻意不在預設裡）
#   WAIT_TIMEOUT=600 cd/apply.sh local app   拉長等待（例如模型第一次載入很慢時）
#
# 只負責「部署」這一步。部署前要過哪道關由觸發的那一方決定：
#   ./gradlew deployApp 先跑測試、之後的 CD 是等 CI 綠燈。這裡不重複檢查。
#
# 一次只換一個服務、每個都帶 --no-deps，理由見 cd/README.md「為什麼每個服務分開換」。

set -euo pipefail
cd "$(dirname "$0")/.."

env_name="${1:?用法：cd/apply.sh <env> [服務...]，env 是 cd/envs/ 底下的檔名（例如 local）}"
shift
env_file="cd/envs/$env_name.env"
[ -f "$env_file" ] || { echo "沒有這個環境：$env_file" >&2; exit 1; }
# shellcheck source=/dev/null
source "$env_file"
: "${COMPOSE_FILES:?$env_file 要設定 COMPOSE_FILES}"

compose_args=()
for f in $COMPOSE_FILES; do compose_args+=(-f "$f"); done

# 版本號（#25）：compose 拿它當映像檔的 tag、傳進 build 寫進 /actuator/info 和 label。規則在 cd/version.sh
APP_VERSION="$(cd/version.sh)"
GIT_COMMIT="$(git rev-parse HEAD)"
export APP_VERSION GIT_COMMIT

# 沒指定就部署 app 再 web：先換後端，前端換上去時打的就是新的 API
services=("$@")
[ ${#services[@]} -gt 0 ] || services=(app web)

# 每個服務等 healthy 的預設上限（秒）。app 要等模型連上，web 幾秒就好，
# llm-chat 要重 build 映像檔加上載 5GB 的模型
default_timeout() {
  case "$1" in
    app) echo 300 ;;
    llm-chat) echo 600 ;;
    *) echo 60 ;;
  esac
}

for svc in "${services[@]}"; do
  timeout="${WAIT_TIMEOUT:-$(default_timeout "$svc")}"
  echo "── 部署 $svc $APP_VERSION（$env_name，最多等 ${timeout} 秒）"
  # --build：映像檔有變才重 build；換了映像檔的容器才會被替換，沒變就什麼都不做。
  # --wait：等 compose 裡的 healthcheck 通過才結束。沒有它，Spring 啟動失敗也會顯示成功。
  docker compose "${compose_args[@]}" up -d --build --no-deps --wait --wait-timeout "$timeout" "$svc"
done

echo "── 完成（$env_name）：$APP_VERSION $(git log -1 --format='%s')"
