# 隱私：使用者的資料流到哪、怎麼擋、怎麼刪

> #52（epic #46 ⑤ Evolve）。對照 [OWASP LLM02 Sensitive Information Disclosure](https://genai.owasp.org/llmrisk/llm022025-sensitive-information-disclosure/)
> 和 [Mercari Production Readiness Checklist](https://github.com/mercari/production-readiness-checklist) 的 “Non-sensitive log”。
> 實測日期：2026-10-19，本機 `deploy/` + `ops/` 兩組 compose。

一句話：**使用者說的話只存在 PostgreSQL（和送去模型的那一次請求），不進 log、trace、指標；要求刪除時一個 API 刪乾淨；觀測資料有保留期限。**

## 1. 盤點：哪些是個資、流到哪裡

使用者會交給我們的東西：

| 個資 | 例子 | 從哪裡進來 |
|---|---|---|
| 對 AI 助理說的話 | 「明天陪王小明看身心科」 | `POST /api/calendar/assistant` |
| AI 助理的回覆 | 會轉述行程標題 | 同上的回應 |
| 行程的標題、地點、備註 | 「牙醫回診」、「聯絡 xxx@example.com」 | `POST /api/calendar/events`、助理的工具結果 |
| 帳號 | 帳號名稱、密碼雜湊；Google／GitHub 給的顯示名稱和使用者 ID（`sub`） | 註冊、第一次用 Google 登入 |

這些東西可能流到的每個地方，修之前、修之後：

| 地方 | 修之前 | 修之後 | 怎麼確認 |
|---|---|---|---|
| PostgreSQL `calendar_event`、`calendar_assistant_message`、`account`、`account_identity` | 存著（本來就該存） | 存著，可以刪（§4） | `PrivacySystemTest` |
| **app 的 access log → Loki** | ❌ 請求、回應的 body 整包記下來：使用者的話、助理回覆、行程標題、email | ✅ 只記方法、路徑、狀態碼、耗時、大小、traceId | §2 實測、`AccessLogFilterTest` |
| **app 的 AI 助理 log → Loki** | ❌ `AI 助理回覆：... reply=<全文>`、工具參數 | ✅ 只記 conversation id、步數、工具名稱、回覆字數 | §2 實測、`CalendarAssistantTest` |
| 例外訊息 → log | 一處會把資料庫裡的工具訊息 JSON 整段放進訊息 | 只放長度 | 程式審查（`JdbcAssistantMemoryRepository.read`） |
| Spring AI 的 observation → Tempo | ✅ 沒有內容：span 只有模型名稱、token 數、工具名稱（`spring.ai.model.request.tool.names`） | ✅ 同左，三個開關明寫 `false` | §2 實測、`PrivacySystemTest` |
| Spring AI 的 prompt／completion log → Loki | ✅ 預設關 | ✅ 明寫 `false`，Alloy 再擋一次 | §3 實測 |
| 指標 → Mimir | ✅ label 只有路徑樣板（`/api/calendar/events/{id}`）、狀態碼、模型名稱 | 同左 | Micrometer 的 `uri` 是樣板，不是實際網址 |
| nginx（web）的 access log → Loki | ✅ 預設的 combined 格式：請求行（路徑 + query string）、狀態、大小，沒有 body | 同左 | `web/nginx.conf` 沒改 `log_format`；目前 query string 只有日期 |
| llama-server（本機模型）的 log → Loki | ✅ 只有耗時、token 數，沒有 prompt | 同左 | §2 實測（找金絲雀字串，`llm-chat` 一行都沒有） |
| Bedrock（雲端模型） | 每次請求把對話送過去（這是功能本身） | 同左 | AWS：Bedrock 不保存、不拿去訓練；model invocation logging 預設關閉，`infra/` 沒有開它 |

## 2. 實測：送一段帶假個資的對話，到 Loki、Tempo 找

做法：[`ops/privacy/send-canary.py`](../ops/privacy/send-canary.py) 照前端的方式註冊、登入、新增一筆行程、問助理一句話。
假個資（王小明、`0912-345-678`、`wang.xiaoming@example.com`）裡都夾著一個金絲雀字串（`CANARY` + 8 碼），
再用 [`ops/privacy/find-in-loki.py`](../ops/privacy/find-in-loki.py) 找它。

```bash
python ops/privacy/send-canary.py                  # 印出這次的 CANARYxxxxxxxx
python ops/privacy/find-in-loki.py CANARYxxxxxxxx  # Loki：所有 service、最近一小時
```

Grafana 裡等價的 LogQL：`{service=~".+"} |= "CANARYxxxxxxxx"`。Tempo 用 trace id 抓整棵 trace 再搜字串
（`/api/datasources/proxy/uid/tempo/api/v2/traces/<traceId>`）。

### 修之前（master `73d6cb5`）

Loki 找到 3 行，全在 `app`（`llm-chat`、`web`、`postgres` 都沒有）：

```
access | POST /api/calendar/assistant {"conversationId": "4c26813d-…", "message": "CANARY7b307f74 2030 年 1 月 15 號有什麼行程？
         順便提醒我打給王小明 0912-345-678"} -> 200 {"conversationId":"4c26813d-…","reply":"2030年1月15日（週二）上午10點到11點有行程：
         「CANARY7b307f74 陪王小明看身心科 0912-345-678」。已提醒你打給王小明。","proposals":[]} (3084 ms)
CalendarAssistant | AI 助理回覆：conversation=4c26813d-… steps=2 proposals=0 reply=2030年1月15日（週二）上午10點到11點有行程：
         「CANARY7b307f74 陪王小明看身心科 0912-345-678」。已提醒你打給王小明。
access | POST /api/calendar/events {"events": [{"title": "CANARY7b307f74 陪王小明看身心科 0912-345-678", …,
         "note": "聯絡 wang.xiaoming@example.com"}]} -> 201 {"events":[{"id":"0db3102f-…","title":"CANARY7b307f74 陪王小明看身心科 …
```

Tempo：同一次請求的兩棵 trace（`ef3ad75b…`、`172ae8e3…`，14 個 span）裡找不到金絲雀字串和電話。
span 上跟模型有關的只有 `gen_ai.request.model`、`gen_ai.usage.*_tokens`、`spring.ai.model.request.tool.names` 這類。
→ **洩漏點在 log，不在 trace**。

### 修之後（這個 PR）

```
$ python ops/privacy/find-in-loki.py CANARY3ff9ae78
Loki 裡找不到
$ python ops/privacy/find-in-loki.py 王小明
Loki 裡找不到
```

反例：正常該有的照樣在（同一段時間 `{service="app"} | json | log_logger=~"access|.*CalendarAssistant"`）：

```
POST /api/auth/login -> 204 (109 ms, 49 B in, 0 B out)              | traceId 7b7855cdcb285445f27c83e64fe5abcd
POST /api/calendar/events -> 201 (46 ms, 185 B in, 259 B out)       | traceId 95b95be6062f86a748e781acd91ce6df
AI 助理呼叫工具：conversation=18861eb7-… tool=list_events             | traceId cfe094dbd69b014573c50d871312db3e
AI 助理回覆：conversation=18861eb7-… steps=2 proposals=0 chars=76     | traceId cfe094dbd69b014573c50d871312db3e
POST /api/calendar/assistant -> 200 (2978 ms, 170 B in, 233 B out)  | traceId cfe094dbd69b014573c50d871312db3e
GET /api/auth/me -> 401 (15 ms, -1 B in, 24 B out)                  | traceId edbad25e7cae28c47f4f3bd497b8bdf0
```

延遲、錯誤（401）、trace id（Loki → Tempo 的跳轉）、助理走了幾步、叫了哪個工具都還在。Tempo 的兩棵 trace（9、5 個 span）照樣完整、照樣沒有內容。

### 修了什麼（第一道：app）

| 檔案 | 改了什麼 |
|---|---|
| `AccessLogFilter` | 不記 body，改記 `http.request.body.bytes`、`http.response.body.bytes`；不記 query string。拿掉 `ContentCaching*Wrapper`，回應不再整包攔在記憶體裡 |
| `CalendarAssistant` | 回覆、工具參數、被擋下的那句話都不記，只記 conversation id、步數、工具名稱、字數 |
| `JdbcAssistantMemoryRepository` | 讀不懂的工具訊息，例外訊息只放長度 |
| `application.properties` | `spring.ai.chat.observations.log-prompt`、`log-completion`、`spring.ai.tools.observations.include-content` 明寫 `false` |

代價：以前在 Loki 一筆就看得到「使用者送了什麼、回了什麼」，現在看不到。要查某次對話：
請使用者提供畫面上的追蹤碼（trace id）→ Loki／Tempo 找到那次請求的 conversation id → 在資料庫裡看那個人自己的對話。
內容只留在一個有權限控管、刪得掉的地方。

## 3. 第二道：Alloy 在送進 Loki 之前再擋一次

第一道哪天破了（有人為了除錯打開 `log-prompt`、新寫的 log 順手印了使用者的話），進了 Loki 就難收回：
Loki 沒有逐筆刪除，只能等保留期限過。所以 `ops/alloy/config.alloy` 在 `loki.source.docker` 和 `loki.write` 中間加了 `loki.process "redact"`：

1. Spring AI 印 prompt／回覆的兩個 logger（`ChatModelPromptContentObservationHandler`、`ChatModelCompletionObservationHandler`）整行丟掉
2. email → `<email>`、台灣手機 → `<phone>`、身分證字號 → `<national-id>`，那一行其他內容照留

驗證：用一個貼著 `com.docker.compose.project=talkcal-deploy` 標籤的假容器（`alpine`）噴兩種 log，模擬「第一道破了」：

```
{"log":{"logger":"org.springframework.ai.chat.observation.ChatModelPromptContentObservationHandler"},"message":"Chat Model Prompt Content: PROBE… 陪王小明看身心科"}
{"log":{"logger":"access"},"message":"PROBE… 聯絡 wang.xiaoming@example.com 0912-345-678 A123456789 trace=0a0912345678ff dur=1409123456789"}
```

修之前（`find-in-loki.py PROBE1792389410 redact-probe`）兩行都原樣進了 Loki：

```
redact-probe access | PROBE1792389410 聯絡 wang.xiaoming@example.com 0912-345-678 A123456789 trace=0a0912345678ff dur=1409123456789
redact-probe org.springframework.ai.chat.observation.ChatModelPromptContentObservationHandler | Chat Model Prompt Content: PROBE1792389410 陪王小明看身心科
```

修之後：

```
$ python ops/privacy/find-in-loki.py PROBE1792414531 redact-probe
redact-probe access | PROBE1792414531 聯絡 <email> <phone> <national-id> trace=0a0912345678ff dur=1409123456789
找到 9 行                     ← 9 次 access 那行，個資都換成記號；prompt 那行一次都沒進來

$ curl -s http://127.0.0.1:12345/metrics | grep llm_content
loki_process_dropped_lines_total{component_id="loki.process.redact",reason="llm_content"} 9
```

丟掉幾行看得到（Alloy 的指標），不是悄悄不見：之後可以對它設告警，「有人打開了 prompt log」會被發現。

反例：`trace=0a0912345678ff`（十六進位中間剛好有一段像電話）、`dur=1409123456789`（長數字）沒有被誤遮 —— 規則用 `\b` 字邊界。

擋不到的：一句自由的中文（「陪王小明看身心科」）規則認不出來。所以這是第二道，不是唯一一道；第一道由 `PrivacySystemTest` 焊住。

## 4. 使用者要求刪除：`DELETE /api/auth/me`

登入的使用者呼叫它，就刪掉**自己**的：

| 資料 | 表 | 怎麼刪 |
|---|---|---|
| 行程 | `calendar_event` | `JdbcOwnerDataAdapter`，一個交易 |
| AI 助理的對話記憶（含工具呼叫、結果） | `calendar_assistant_message` | 同上 |
| 帳號（名稱、密碼雜湊、顯示名稱） | `account` | `JdbcAccountAdapter.delete` |
| Google／GitHub 的對應（`sub`） | `account_identity` | 外鍵 `ON DELETE CASCADE`，跟帳號同一句 |
| 登入狀態 | session（app 記憶體）、`XSRF-TOKEN` cookie | 刪完由 Spring Security 登出 |

```bash
# 前端之外要手動刪（瀏覽器的 cookie 帶著 session 和 XSRF-TOKEN）：
curl -X DELETE http://localhost:18080/api/auth/me -b cookies.txt -H "X-XSRF-TOKEN: <cookie 裡的值>"
# → 204；之後 GET /api/auth/me 是 401，原本的帳密登不進來
```

設計：

- **只刪得到自己**：路徑上沒有 id，身分只從 session 來；要刪別人的帳號，連寫法都沒有
- **授權和 CSRF**：沒登入 401；別的網站騙瀏覽器送（沒有 `X-XSRF-TOKEN`）403，什麼都沒刪
- **跨模組**：calendar 和 auth 互不認識（ArchitectureTest）。auth 定義 `ForgetAccountDataPort`（「刪帳號前，請別的模組忘掉這個人」），
  `app.config` 把它接到 calendar 的 `ForgetOwnerUseCase`
- **順序：先刪資料，最後刪帳號**。兩邊不在同一個交易，中途失敗時留下的永遠是「帳號還在」，使用者再按一次就補完（每一步都可以重複執行）。
  反過來的話，帳號沒了、資料刪一半，使用者連再要求一次都沒辦法
- **留一筆紀錄**：`刪除帳號：account=<UUID>`（只有 id），使用者之後問「刪了沒」有紀錄可查

測試（`PrivacySystemTest`，真的 PostgreSQL、真的 Spring Security）：

| 題目 | 反例 |
|---|---|
| amy 刪帳號：帳號、Google 對應、行程、對話，以及**資料庫裡每一張帶 `owner_id` 欄位的表**都歸零 | bob 的每一列都還在，月曆照常 |
| 刪完 session 失效（401），帳密登不進來 | bob 照常登入 |
| 沒帶 CSRF token：403，一列都沒少；沒登入：401 | — |

「每一張帶 `owner_id` 的表」是從 `information_schema` 掃出來的，不是手寫清單：之後新增帶擁有者的表（冪等 key #51、歸檔 #54），
刪除流程忘了加，這題就紅燈。實測反例：把 `JdbcOwnerDataAdapter.TABLES` 拿掉 `calendar_assistant_message`，這題 FAILED。

已知限制：

- **同一個人在別的裝置上的 session**：刪除只登出發出請求的這一個。另一台的 session 在 `GET /api/auth/me` 會是 401（帳號查不到），
  但它最多 8 小時（`server.servlet.session.timeout`）內還能新增行程，新增的資料不屬於任何帳號。要補的話是「每個請求確認帳號還在」或改用 Spring Session 集中管理 session
- **備份**：刪除不會回頭改已經做好的資料庫備份。備份的保留期限（#45）就是刪除真正生效的最晚時間
- **前端還沒有按鈕**：目前是 API（Swagger UI 上試得到）

## 5. 觀測資料留多久

| 資料 | 存在哪 | 保留期限 | 設定 |
|---|---|---|---|
| log | Loki | **7 天**（以前沒設 = 永遠不刪） | `ops/loki/loki-config.yaml`：`limits_config.retention_period: 168h`、`compactor.retention_enabled: true` |
| trace | Tempo | 72 小時 | `ops/tempo/tempo.yaml`：`backend_worker.compaction.block_retention` |
| 指標 | Mimir | 沒設（不含個資：只有路徑樣板、狀態碼、數字） | — |
| AI 助理的對話 | PostgreSQL | 30 天沒說話就刪（`AssistantMemoryCleanup`，每天凌晨四點） | `calendar.assistant.retention=30d` |
| 行程、帳號 | PostgreSQL | 直到使用者刪除 | §4 |

為什麼 log 是 7 天：回頭查「上週出了什麼事」夠用；更久的趨勢看 Mimir 的指標，不需要原始 log。
log 裡已經沒有對話內容，但還有帳號 ID、路徑、時間 —— 不該比帳號本身活得更久。

確認 Loki 吃到設定：`curl -s http://127.0.0.1:3100/config | grep retention` → `retention_enabled: true`、`retention_period: 1w`。

## 6. 新增功能時的檢查清單

- 新的 log：只記「發生了什麼」（id、數量、狀態、耗時），不記「說了什麼」
- 新的表帶使用者資料：加 `owner_id` 欄位，並加進 `JdbcOwnerDataAdapter.TABLES`（漏了 `PrivacySystemTest` 會紅燈）
- 新的 query string：會不會帶使用者打的字（搜尋）？nginx 的 access log 會記下整個網址
- 打開 Spring AI 的內容記錄、`include-content`：先改這份文件，說明為什麼、存多久、誰看得到
