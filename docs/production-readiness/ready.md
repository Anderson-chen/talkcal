# ① 能不能上線（Ready）

[← 回上線準備清單](../production-readiness.md)

<a id="architecture-test"></a>
### 分層與架構守護 ✅

[ArchitectureTest](../../backend/src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java) 用 ArchUnit 把分層規則寫成測試，違反規則就會紅燈。

<a id="real-db-tests"></a>
### 整合測試用真的資料庫 ✅

用 Testcontainers 起真的 PostgreSQL。不用 H2 的理由寫在 [build.gradle](../../backend/build.gradle)。

<a id="api-contract"></a>
### API 契約文件 ✅

[OpenApiDocumentationTest](../../backend/src/test/java/io/github/andersonchen/talkcal/OpenApiDocumentationTest.java)

<a id="system-test"></a>
### 整個應用接起來的系統測試 ✅

[SystemTest](../../backend/src/integrationTest/java/io/github/andersonchen/talkcal/SystemTest.java)：起整個 Spring Boot（隨機埠），用 Testcontainers 接真的 PostgreSQL，啟動時 Flyway 照正式流程跑完所有 migration，再用真的 HTTP 打 API。其他測試各自只接一段真的東西，這一支抓的是「各段都對、接起來才壞」的問題：bean 組裝、設定檔、SQL 和 schema 對不上、JSON 序列化、例外對到哪個狀態碼。

| 題目 | 需要模型 | 在哪裡跑 |
|---|---|---|
| 行程新增 → 讀得到 → 刪掉 → 讀不到，再刪一次是 404 | 不用 | 本機和 GitHub CI |
| 不合規的行程回 400，而且一筆都沒存 | 不用 | 本機和 GitHub CI |
| 問助理「明天晚上有什麼行程」：回答裡有資料庫裡那筆的標題，對話（含工具呼叫）存進資料庫、讀得回來 | 要 | 只有本機（GitHub 沒有 GPU，自動跳過） |

只當黑箱用：送 JSON 字串、讀 JSON，不引用 controller 的 record，API 的形狀改了這裡會紅燈。

<a id="frontend-test"></a>
### 前端測試 ❌

[web/package.json](../../web/package.json) 沒有任何測試工具，目前只有 `vue-tsc` 型別檢查。

<a id="ai-eval"></a>
### AI 品質回歸（eval） ❌

改了 prompt 或換了模型之後，沒辦法知道它有沒有變笨。需要一組固定題目，加上自動評分。

<a id="auth"></a>
### 認證與授權 ❌

沒有登入功能，任何人只要知道 conversation id 就能讀那段對話。

<a id="rate-limit"></a>
### 濫用防護（rate limit） ✅

AI 助理的發問（`POST /api/calendar/assistant`）每次都會叫模型，一輪還可能叫好幾次：本機是 GPU 塞滿，雲端是 Bedrock 按 token 計費（`LLM10` Unbounded Consumption）。
[web/nginx.conf](../../web/nginx.conf) 用 nginx 內建的 `limit_req` 擋兩道，超過回 `429` 和 `{"error": ...}`，前端照常顯示訊息：

| 限制 | 擋什麼 | 為什麼是這個數字 |
|---|---|---|
| 每個 IP 每分鐘 10 次（可多衝 5 次） | 一個人用腳本狂打 | 正常聊天約 10–20 秒問一次 |
| 全站每分鐘 30 次（可多衝 10 次） | 換很多 IP 一起打：帳單和 GPU 的總上限 | 2026-10-12 量到每分鐘 60 次、問題隨機混合時模型就塞爆回 502（[postmortem](../postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)），要比它低 |

查行程、看對話紀錄很便宜，不限。單次請求另外有 `max-tokens=2048` 擋生成暴走。
放在 nginx 而不是 Caddy 或 app：本機和雲端都經過它；`limit_req` 是內建的（Caddy 要另外編外掛）；被擋的請求進不到 app、碰不到模型。
雲端上 nginx 前面是 Caddy，所以用 `X-Forwarded-For` 認出真正的使用者 IP —— 前提是 web 的埠只綁 127.0.0.1，外面只能經過 Caddy，而 Caddy 會覆寫使用者自己送的那一份。

證據：連打 20 次，前 6 次放行、之後都是 429（帶 `Retry-After: 60`），同時查行程照常 200；
換 20 個不同 IP 各打一次，前 11 次放行、之後被全站上限擋下，nginx log 記的是 `zone "assistant_all"` 和偽裝的那個 IP（真實 IP 有認對）。

擋不住、或刻意接受的：
- **全站上限會連累正常使用者**：有人換 IP 狂打時，其他人也會拿到 429。這是刻意的取捨 —— 寧可服務暫時不能用，也不要帳單失控
- 同一個 NAT 後面的多個人（公司、宿舍）共用一個 IP 的額度
- 計數在 nginx 的記憶體裡：重啟就歸零；之後變成多台時，每台各算各的
- 沒有 AWS 的帳單告警：真的被打穿時，要等月底才知道花了多少

<a id="agent-permissions"></a>
### Agent 權限範圍 ✅

[CalendarTools](../../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/in/assistant/CalendarTools.java) 只給模型三個工具：查行程、找空檔、提議行程。提議不會存檔，一定要使用者在畫面上按確認。agent 沒有刪除或修改行程的工具（`LLM06` Excessive Agency：只給完成任務需要的動作，寫入要人確認）。

<a id="prompt-injection"></a>
### Prompt injection ❌（部分做到）

權限收窄之後，模型就算被注入，最多也只能提議錯的行程，或在回答裡說錯話。剩下的風險是間接注入（`LLM01`）：`list_events` 會把行程標題、備註原封不動交給模型，有人把指令寫進行程標題，模型就會讀到。目前沒有對這類輸入做標記或過濾，也沒有測試。

<a id="output-handling"></a>
### 模型輸出當成不可信資料 ✅

前端只用 `{{ }}` 顯示模型的回答，沒有用 `v-html`。模型就算吐出 HTML 或 script，也只會被當成文字顯示（`LLM05` Improper Output Handling）。

<a id="timeouts"></a>
### 外部相依的逾時與重試 ✅

[application.properties](../../backend/src/main/resources/application.properties)：呼叫模型的逾時是 2 分鐘；SDK 預設的重試關掉了（`max-retries=0`）。理由寫在設定旁邊：本機模型塞爆的時候，重試只會讓它更塞。

<a id="secrets"></a>
### 祕密管理 ❌（部分做到）

[application.properties](../../backend/src/main/resources/application.properties) 直接寫著資料庫密碼。開發時可以這樣，上線時要改成從環境變數或 secrets 注入。

<a id="container"></a>
### Container 加固 ✅

[Dockerfile](../../backend/Dockerfile)：兩段式 build、不用 root 執行、用 exec 形式啟動，讓 JVM 收得到 SIGTERM。

<a id="dependency-scan"></a>
### 相依套件與映像檔掃描 ✅

CI 的 `vulns` job 會 build 出 app、web 映像檔，連同 postgres 用 [ci/vulns.sh](../../ci/vulns.sh)（Trivy）掃，有 HIGH、CRITICAL 就紅燈，是合併的必過檢查。
每天台灣 09:00 再掃一次 master：新的 CVE 會在程式碼沒變的時候冒出來，失敗時 GitHub 寄信通知。
掃映像檔而不是 build.gradle：作業系統套件和 jar 裡的 Java 函式庫一次看得到，掃的就是會上線的東西。
對應 OWASP Top 10:2025 的 `A03` Software Supply Chain Failures（2021 版的 A06 Vulnerable and Outdated Components）。
沒用 OWASP 自己的 Dependency-Check：它只看 Java、npm 這些函式庫，看不到基底映像檔裡的 openssl、curl；Trivy 兩種都掃。

證據（反例）：同一個 `alpine:3.24` 基底只多放一個 `log4j-core-2.14.1.jar`，就抓到 Log4Shell（`CVE-2021-44228`，CRITICAL），exit 1；
映像檔不存在（Trivy 自己出錯）也是 exit 1，不會被當成「沒有漏洞」。

第一次掃到的（2026-10-14，只算 HIGH、CRITICAL 而且有修補版本的）和處理方式（[#85](https://github.com/Anderson-chen/talkcal/issues/85)）：

| 映像檔 | 修之前 | 在哪 | 怎麼處理 | 修之後 |
|---|---|---|---|---|
| app | 13 | Tomcat 11.0.24（3 個 CRITICAL）、Jackson 2.22.1 和 3.1.5 | Boot 4.1.1 已是最新，[build.gradle](../../backend/build.gradle) 覆寫成 Tomcat 11.0.27、Jackson 2.22.3、3.1.7（只升 patch） | 0 |
| app | 2 | openssl | 重新 build，拉到最新的 temurin 基底 | 0 |
| web | 44 | 基底 `nginx:1.29-alpine`（已不再更新） | 換 stable 線 `nginx:1.30-alpine`，再 `apk upgrade` 補上 Alpine 已發的修補 | 0 |
| postgres | 25 | `gosu` 的 Go 標準函式庫 | 修不了也碰不到，寫進 [ci/trivyignore.yaml](../../ci/trivyignore.yaml)，附理由、2027-01-14 到期 | 0（忽略） |

擋不住：上游還沒修的漏洞（`--ignore-unfixed`，沒得升就不擋）、MEDIUM 以下。
副作用：master 上有新漏洞時 CI 是紅的，CD watcher 不會部署 —— 要先修掉或評估後寫進忽略清單。

<a id="secret-scan"></a>
### 祕密掃描 ✅

GitHub 自己的 push protection 已經擋住大部分情況；gitleaks 補的是本機就擋、通用規則、不綁平台（取捨見 [ci/README.md](../../ci/README.md#github-已經會擋為什麼還要-gitleaks)）。

三道，由近到遠：

| 關卡 | 掃什麼 | 擋住時 key 在哪 |
|---|---|---|
| pre-push 跑 [ci/secrets.sh](../../ci/secrets.sh)（gitleaks） | 這次要推的 commit，包括中間加了又刪的 | 還在本機，改掉再推就好 |
| GitHub push protection（repo 設定，已開） | 各家服務的 key 格式（AWS、OpenAI…） | 送到 GitHub 但被拒收 |
| CI 的 `secrets` job（同一支 script） | 整個歷史 | **已經公開**，要作廢重發 |

證據：在暫存分支 commit 一把隨機產生的 GitHub token、下一個 commit 刪掉，`ci/secrets.sh HEAD --not --remotes` 1 秒內抓到（`github-pat`）、exit 1；完整歷史掃過一遍，沒有誤判。

擋不住：寫死的低熵密碼。[application.properties](../../backend/src/main/resources/application.properties) 的資料庫密碼就沒被抓到 —— 那是[祕密管理](#secrets)要解決的事，不是掃描。

<a id="supply-chain"></a>
### 第三方映像檔與模型檔的來源 ✅

[compose](../../deploy/compose.yaml) 裡的 postgres、llama.cpp 映像檔都釘死版本，不用 `latest`。模型檔複製進 volume 時會比對 SHA256，對不上的話模型服務就不會啟動（`LLM03` Supply Chain）。
