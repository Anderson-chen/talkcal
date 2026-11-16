#!/usr/bin/env bash
# 用 promtool 檢查 Mimir 的錄製規則（ops/mimir/rules/）。promtool 和 Mimir 用同一套 PromQL 引擎，算出來的值一樣。
#
#   ci/rules.sh
#
# check rules 抓語法、PromQL 寫錯、名稱重複；test rules 讀入 ops/mimir/slo.test.yaml 的假請求，比對錄出來的值。
# 改了範圍、門檻、算式卻漏想某個情況（沒流量、不在範圍內的路徑），第二步會失敗。
#
# 不放進 checks.sh：那邊的容器沒有 promtool，這支只要一個 Prometheus 映像檔，幾秒就執行完。

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
export MSYS_NO_PATHCONV=1  # 別讓 Git Bash 把 /m 改寫成 Windows 路徑（見 ci/run.sh）

# 版本和 digest 都釘死（理由見 ci/vulns.sh）
image=prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0

# Windows 上 docker 要 C:/... 形式的路徑（pwd -W），Linux、macOS 沒有 -W 就用 pwd
dir=$(pwd -W 2>/dev/null || pwd)/ops/mimir

docker run --rm --volume "$dir:/m:ro" --workdir /m --entrypoint promtool "$image" check rules rules/anonymous/slo.yaml
docker run --rm --volume "$dir:/m:ro" --workdir /m --entrypoint promtool "$image" test rules slo.test.yaml
