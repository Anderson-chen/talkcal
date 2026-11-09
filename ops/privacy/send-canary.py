"""送一段帶假個資的對話進 talkcal，之後到 Loki、Tempo 查這些字還在不在（#52）。

假個資裡都夾著同一個金絲雀字串（CANARY），查的時候只要找它：
    python ops/privacy/send-canary.py [base_url]       # 預設 http://localhost:18090（deploy/ 的 app）

做的事跟前端一樣：註冊、登入（session cookie + XSRF-TOKEN）、新增一筆行程、問 AI 助理一句話。
只用標準函式庫，不必裝套件。印出這次的金絲雀字串和 trace id，查詢指令見 docs/privacy.md。
"""
import http.cookiejar
import json
import sys
import urllib.parse
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18090"
CANARY = "CANARY" + uuid.uuid4().hex[:8]
# 假的：名字、手機、email 都是編的（example.com 是保留給範例用的網域）
NAME, PHONE, EMAIL = "王小明", "0912-345-678", "wang.xiaoming@example.com"

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def xsrf():
    return next((c.value for c in jar if c.name == "XSRF-TOKEN"), "")


def send(method, path, body=None, form=False):
    headers = {}
    data = None
    if body is not None:
        if form:
            data = urllib.parse.urlencode(body).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
    if method != "GET":
        headers["X-XSRF-TOKEN"] = xsrf()
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with opener.open(request, timeout=180) as response:
            return response.status, response.headers.get("X-Trace-Id"), response.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("X-Trace-Id"), e.read().decode()


username = "canary-" + CANARY[-8:]
send("GET", "/api/auth/me")  # 拿 XSRF-TOKEN cookie
print("register", send("POST", "/api/auth/register", {"username": username, "password": "canary-password"})[0])
send("GET", "/api/auth/me")
print("login", send("POST", "/api/auth/login", {"username": username, "password": "canary-password"}, form=True)[0])
send("GET", "/api/auth/me")  # 登入後 token 換新

title = f"{CANARY} 陪{NAME}看身心科 {PHONE}"
status, _, body = send("POST", "/api/calendar/events", {"events": [
    {"title": title, "start": "2030-01-15T10:00:00", "end": "2030-01-15T11:00:00",
     "note": f"聯絡 {EMAIL}"}]})
print("add event", status)

message = f"{CANARY} 2030 年 1 月 15 號有什麼行程？順便提醒我打給{NAME} {PHONE}"
status, _, body = send("POST", "/api/calendar/assistant",
                       {"conversationId": str(uuid.uuid4()), "message": message})
print("assistant", status, body[:200])
print("CANARY =", CANARY)
