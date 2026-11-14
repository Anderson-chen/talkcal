#!/usr/bin/env bash
# 執行 k6，指標經過 Alloy 寫進 Mimir，在 Grafana 的「talkcal — 壓測（k6）」dashboard 跟 app、模型的指標疊在一起看。
#
#   perf/run-to-grafana.sh perf/knee.js
#   STEPS=1,2,4 HOLD_S=10 perf/run-to-grafana.sh perf/knee.js     # 腳本自己的參數照常用環境變數給
#   perf/run-to-grafana.sh perf/load.js -o web-dashboard           # 後面的參數原樣交給 k6 run
#
# 要先啟動 ops/（docker compose --env-file deploy/.env -f ops/compose.yaml up -d）。沒啟動也能壓完，只是 k6 會印出指標送不出去的錯誤。
# 下面的設定少一個 dashboard 就對不上，所以包成一支。

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "用法：perf/run-to-grafana.sh <腳本.js> [其他 k6 run 參數]" >&2
    exit 1
fi

# Alloy 的 OTLP 入口，ops/compose.yaml 發佈在主機的 4318。
# 用 opentelemetry 輸出、不用 experimental-prometheus-rw 的理由見 ops/README.md 的「壓測」
export K6_OTEL_EXPORTER_PROTOCOL=http/protobuf
export K6_OTEL_HTTP_EXPORTER_ENDPOINT=localhost:4318
export K6_OTEL_HTTP_EXPORTER_INSECURE=true

# dashboard 的查詢都寫 k6_ 開頭。不加前綴會出現 checks_total、vus 這種跟別的服務分不清的名字
export K6_OTEL_METRIC_PREFIX=k6_

# 預設的固定 bucket 最大只到 10 秒，模型一排隊，p95 就被壓在 10 秒，看起來像沒再變慢。
# 這是 OpenTelemetry SDK 的標準環境變數，k6 認得；Alloy 要開 send_native_histograms 才收得下
export OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION=base2_exponential_bucket_histogram

# 預設 10 秒。短的壓測一階只有十幾秒，10 秒一次只剩一兩個點，rate() 算不出來
export K6_OTEL_EXPORT_INTERVAL=5s

script=$1
shift
exec k6 run -o opentelemetry "$@" "$script"
