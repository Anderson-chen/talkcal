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
### 整個應用接起來的系統測試 ❌

以前有一支系統測試，對 `/api/chat` 起整個應用、打真的 HTTP、接真的模型，但那個端點拿掉時一起拿掉了。AI 助理目前只有元件層級的整合測試（`CalendarAssistantConversationTest`），還沒有從 HTTP 一路打到模型和資料庫的測試。

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

`/api/calendar/assistant` 每次都會占用 GPU，一輪還可能叫好幾次模型，所以一個人就能把服務塞滿（`LLM10`）。目前只限制了單次請求：`max-tokens=2048` 能擋住一次生成暴走，但沒有限制請求次數。

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
### 相依套件與映像檔掃描 ❌

沒有 Trivy 或 OWASP dependency-check 這類掃描。

<a id="secret-scan"></a>
### 祕密掃描 ❌

沒有 gitleaks 這類工具，不小心把 API key commit 進去也不會被擋下來。現在 repo 裡只有假 key，換成雲端模型之後就會有真的 key。

<a id="supply-chain"></a>
### 第三方映像檔與模型檔的來源 ✅

[compose](../../deploy/compose.yaml) 裡的 postgres、llama.cpp 映像檔都釘死版本，不用 `latest`。模型檔複製進 volume 時會比對 SHA256，對不上的話模型服務就不會啟動（`LLM03` Supply Chain）。
