"""把 k6 --out csv 的結果整理成每 10 秒一列：成功幾個、失敗幾個、失敗佔幾 %。

  python perf/canary/timeline.py perf/results/canary.csv

canary 的反例要看的是「失敗只佔一小部分、而且切回之後就停了」，光看收工的總數看不出時間上的變化。
只讀 http_reqs（每個請求一列，有 status 欄），setup() 裡登入的那幾個請求不算。
"""

import csv
import sys
from collections import defaultdict
from datetime import datetime

BUCKET_S = 10


def main(path: str) -> None:
    buckets: dict[int, list[int]] = defaultdict(lambda: [0, 0])  # [成功, 失敗]
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            if row["metric_name"] != "http_reqs" or row.get("group", "").startswith("::setup"):
                continue
            ts = int(row["timestamp"])
            bucket = ts - ts % BUCKET_S
            buckets[bucket][0 if row["status"] == "200" else 1] += 1

    print(f"{'時間':>8}  {'成功':>5}  {'失敗':>5}  失敗比例")
    for ts in sorted(buckets):
        good, bad = buckets[ts]
        share = bad / (good + bad)
        bar = "#" * round(share * 40)
        print(f"{datetime.fromtimestamp(ts):%H:%M:%S}  {good:>5}  {bad:>5}  {share:6.1%} {bar}")


if __name__ == "__main__":
    main(sys.argv[1])
