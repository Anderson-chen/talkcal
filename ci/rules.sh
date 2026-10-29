#!/usr/bin/env bash
# 檢查 Mimir 的錄製規則（ops/mimir/rules/）：語法對不對、算出來的 SLI、burn rate 對不對。用 promtool，在容器裡跑。
#
#   ci/rules.sh
#
# 兩步：
#   1. promtool check rules：語法、PromQL 寫錯、同一組裡名稱重複。
#   2. promtool test rules ops/mimir/slo.test.yaml：餵假的請求序列，比對錄出來的值（例如 1/64 的 5xx → burn rate 3.125）。
#      改了範圍、門檻、算式卻沒想到某個情況（沒流量、不在範圍內的路徑），這一步會紅。
#
# 為什麼不放進 checks.sh：那邊的容器是 JDK + Node，沒有 promtool；這支只要一個 Prometheus 映像檔，幾秒就跑完。
# promtool 是 Prometheus 的 PromQL 引擎，Mimir 用的是同一套，算出來的值一樣。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 同 run.sh：別讓 Git Bash 把 /m 改寫成 Windows 路徑

# 版本和 digest 都釘死（同 secrets.sh）
image=prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0

# Windows 上 docker 要 C:/... 形式的路徑（pwd -W），Linux、macOS 沒有 -W 就用 pwd
dir=$(pwd -W 2>/dev/null || pwd)/ops/mimir

docker run --rm --volume "$dir:/m:ro" --workdir /m --entrypoint promtool "$image" check rules rules/anonymous/slo.yaml
docker run --rm --volume "$dir:/m:ro" --workdir /m --entrypoint promtool "$image" test rules slo.test.yaml
