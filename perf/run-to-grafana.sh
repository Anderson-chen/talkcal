#!/usr/bin/env bash
# 跑 k6，同時把指標送進 ops/ 的 Grafana（經過 Alloy → Mimir），壓測曲線跟 app、模型的指標疊在同一個時間軸上看。
#
#   perf/run-to-grafana.sh perf/knee.js
#   STEPS=1,2,4 HOLD_S=10 perf/run-to-grafana.sh perf/knee.js     # 腳本自己的參數照常用環境變數給
#   perf/run-to-grafana.sh perf/load.js -o web-dashboard           # 後面的參數原樣交給 k6 run
#
# 要先起 ops/（docker compose --env-file deploy/.env -f ops/compose.yaml up -d），看 Grafana 的「talkcal — 壓測（k6）」dashboard。
# ops/ 沒起也照樣壓得完，只是指標送不出去，k6 會印錯誤訊息。
#
# 為什麼包成一支：下面每一個設定都少不得，漏一個 dashboard 就對不上，靠人記不住。

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "用法：perf/run-to-grafana.sh <腳本.js> [其他 k6 run 參數]" >&2
    exit 1
fi

# 送到 Alloy 的 OTLP 入口（ops/compose.yaml 發佈在主機的 4318，跟 app 送 trace 是同一個）。
# 用 k6 的 opentelemetry 輸出：k6 v2 裡它是正式版；experimental-prometheus-rw 還掛著 experimental
export K6_OTEL_EXPORTER_PROTOCOL=http/protobuf
export K6_OTEL_HTTP_EXPORTER_ENDPOINT=localhost:4318
export K6_OTEL_HTTP_EXPORTER_INSECURE=true          # 本機、不走 TLS

# 名稱加 k6_ 前綴：不加的話會出現 checks_total、vus 這種太通用的名字，跟別的服務撞在一起分不清
export K6_OTEL_METRIC_PREFIX=k6_

# 延遲用 exponential histogram 送：預設的固定 bucket 最大只到 10 秒，
# 模型一塞車 p95 就超過，算出來會被壓在 10 秒，看起來像「沒有再變慢」。
# 這是 OpenTelemetry SDK 的標準環境變數，k6 的 Go SDK 認得它；Alloy 那頭要開 send_native_histograms 才收得下
export OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION=base2_exponential_bucket_histogram

# 每 5 秒送一次（預設 10 秒）：短的壓測一階只有十幾秒，10 秒一次的話一階只剩一兩個點，rate() 算不出來
export K6_OTEL_EXPORT_INTERVAL=5s

script=$1
shift
exec k6 run -o opentelemetry "$@" "$script"
