"""在 Loki 裡找一段字（#52）：送完 send-canary.py 之後，看金絲雀字串有沒有被收進去。

    python ops/privacy/find-in-loki.py CANARY1a2b3c4d            # 所有 service、最近一小時
    python ops/privacy/find-in-loki.py CANARY1a2b3c4d app 6h     # 只看 app、最近六小時

印出每一行找到的 log（service、logger、訊息），一行都沒有就印「Loki 裡找不到」。
等 Loki 收進來要幾秒；剛送完就查，可能還沒進去。直連 ops/ 發佈在主機的 127.0.0.1:3100。
"""
import json
import sys
import urllib.parse
import urllib.request

needle = sys.argv[1]
service = sys.argv[2] if len(sys.argv) > 2 else ".+"
since = sys.argv[3] if len(sys.argv) > 3 else "1h"

query = '{service=~"%s"} |= "%s"' % (service, needle)
url = "http://127.0.0.1:3100/loki/api/v1/query_range?" + urllib.parse.urlencode(
    {"query": query, "since": since, "limit": 100})
with urllib.request.urlopen(url, timeout=30) as response:
    result = json.load(response)["data"]["result"]

found = 0
for stream in result:
    for _, line in stream["values"]:
        found += 1
        try:
            entry = json.loads(line)
            logger = (entry.get("log") or {}).get("logger", "")
            print(stream["stream"].get("service"), logger, "|", entry.get("message", line))
        except ValueError:
            print(stream["stream"].get("service"), "|", line)
print(f"query: {query}")
print(f"找到 {found} 行" if found else "Loki 裡找不到")
