#!/usr/bin/env bash
# 把這台機器上的服務換成目前 checkout 的版本，等服務 healthy 才結束。
# 本機和雲端都執行這一支，環境的差異寫在 cd/envs/<env>.env。
#
#   cd/apply.sh local                 部署 app、web
#   cd/apply.sh local web             只部署前端
#   cd/apply.sh local llm-chat        只換模型（要重 build、重新載入模型，所以不在預設裡）
#   WAIT_TIMEOUT=600 cd/apply.sh local app   拉長等待
#
# 這裡不執行測試、不看 CI：部署前要過哪道關由觸發的一方決定（./gradlew deployApp 先執行測試，watcher 等 CI 綠燈）。
# 一次換一個服務、每個都帶 --no-deps，理由見 cd/README.md「為什麼每個服務分開換」。

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

# compose 拿 APP_VERSION 當映像檔的 tag，兩個值都會貼成映像檔的 label。版本號的規則在 cd/version.sh
APP_VERSION="$(cd/version.sh)"
GIT_COMMIT="$(git rev-parse HEAD)"
export APP_VERSION GIT_COMMIT

# 先換後端再換前端：新的前端換上去時，呼叫的已經是新的 API
services=("$@")
[ ${#services[@]} -gt 0 ] || services=(app web)

# 等 healthy 的預設上限（秒）。app 要等 Spring 啟動、Flyway 執行完 migration；llm-chat 要重 build 映像檔再載入 5 GB 的模型
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
  # --build 只在映像檔有變時重 build，沒變就不換容器。
  # --wait 等 healthcheck 通過才結束：不加的話 Spring 啟動失敗也會顯示成功
  docker compose "${compose_args[@]}" up -d --build --no-deps --wait --wait-timeout "$timeout" "$svc"
done

echo "── 完成（$env_name）：$APP_VERSION $(git log -1 --format='%s')"
