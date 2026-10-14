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
### 濫用防護（rate limit） ❌

`/api/calendar/assistant` 每次都會占用 GPU，一輪還可能叫好幾次模型，所以一個人就能把服務塞滿（`LLM10`）。目前只限制了單次請求：`max-tokens=2048` 能擋住一次生成暴走，但沒有限制請求次數。2026-10-12 量到每分鐘 60 個請求、問題隨機混合時，模型的 KV cache 就塞爆、回 502（見 [postmortem](../postmortems/2026-10-12-deploy-cuts-in-flight-requests.md) 的附帶發現），門檻要比這個低。

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
### 相依套件與映像檔掃描 ❌（只回報、還不擋）

CI 的 `vulns` job 會 build 出 app、web 映像檔，連同 postgres 用 [ci/vulns.sh](../../ci/vulns.sh)（Trivy）掃，結果貼在 Actions 結果頁。
掃映像檔而不是 build.gradle：作業系統套件和 jar 裡的 Java 函式庫一次看得到，掃的就是會上線的東西。

第一次掃（2026-10-14，只算 HIGH、CRITICAL 而且有修補版本的）：

| 映像檔 | 數量 | 在哪 | 怎麼修 |
|---|---|---|---|
| app | 13 | Tomcat 11.0.24（1 個 CRITICAL）、Jackson 2.22.1 | Spring Boot 4.1.1 已是最新，要在 Gradle 手動拉高版本 |
| app | 2 | openssl | 重新 build，最新的 temurin 基底已經是 0 |
| web | 44 | 基底 `nginx:1.29-alpine`（curl、expat…） | 1.29 已不再更新，換 1.30 剩 3 個 |
| postgres | 25 | `gosu` 的 Go 標準函式庫 | 上游問題，最新版也一樣；gosu 只在啟動時切換使用者，碰不到外部輸入 |

還沒做完的：修掉上面這些、把 gosu 寫進忽略清單並附理由，然後把 `vulns` 改成會擋、加進必過檢查，再加每天的排程掃描（新漏洞會在程式碼沒變的時候冒出來）。
只回報的階段沒有人會被通知 —— 要有人去看結果頁才知道。

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
