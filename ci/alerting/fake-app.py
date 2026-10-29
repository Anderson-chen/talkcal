# 假 app：只吐 /actuator/prometheus，給 ci/alerting.sh 用。
#
# 要驗的是「指標 → Mimir → Grafana → 寄信」這條鏈，不是 app，所以只吐 SLO 錄製規則（ops/mimir/rules/anonymous/slo.yaml）
# 讀的那兩個指標：http_server_requests_seconds_count、_bucket。uri 用 /api/calendar/events，落在 calendar-* 的範圍裡。
#
# 每被抓一次就當作進來 REQUESTS_PER_SCRAPE 個請求，全部比 0.3 秒快（延遲 SLO 不燒）。
# 狀態碼看 /tmp/mode：沒有這個檔或內容是 ok → 200；內容是 5xx → 500。切換：
#   docker compose exec fake-app sh -c 'echo 5xx > /tmp/mode'
# 「app 掛了」直接 docker compose stop fake-app。

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REQUESTS_PER_SCRAPE = 20
LABELS = 'method="GET",uri="/api/calendar/events",outcome="{outcome}",status="{status}"'
# Spring 的 histogram 每個 bucket 是累計的（le 以內的都算）。0.3 是延遲 SLO 的門檻，一定要有
BUCKETS = ["0.3", "8.0", "+Inf"]

counts = {"200": 0, "500": 0}


def mode():
    try:
        with open("/tmp/mode") as f:
            return f.read().strip()
    except FileNotFoundError:
        return "ok"


def render():
    lines = [
        "# TYPE http_server_requests_seconds histogram",
    ]
    for status, n in counts.items():
        labels = LABELS.format(outcome="SUCCESS" if status == "200" else "SERVER_ERROR", status=status)
        for le in BUCKETS:
            lines.append(f'http_server_requests_seconds_bucket{{{labels},le="{le}"}} {n}')
        lines.append(f"http_server_requests_seconds_count{{{labels}}} {n}")
        lines.append(f"http_server_requests_seconds_sum{{{labels}}} {n * 0.05}")
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/actuator/prometheus":
            self.send_error(404)
            return
        counts["500" if mode() == "5xx" else "200"] += REQUESTS_PER_SCRAPE
        body = render().encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass  # 每 15 秒一行，沒用


ThreadingHTTPServer(("0.0.0.0", 8091), Handler).serve_forever()
