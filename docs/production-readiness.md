# 上線準備清單（Production Readiness）

talkcal 不只要「能跑」，還要能回答：**上線之後要顧慮哪些事？哪些做了、哪些還沒？**
這份清單就是那個答案。每補完一項就把狀態改掉並附上證據的連結；還沒做的也照實列出來，已知的缺口比假裝沒有缺口好。

最後盤點：2026-10-08

## 怎麼分類

面向一個一個列很容易漏，所以照產品的生命週期分成五段。被問到「上線要注意什麼」，照這五段走一輪就不會漏掉一大塊。
五段是自己整理的分法，裡面的項目拿下面這幾份業界清單逐條對照過（2026-10-08），對方有、這裡沒有的都補進來了：

- [Google SRE Book：Launch Coordination Checklist](https://sre.google/sre-book/launch-checklist/)：Google 上線前逐項確認的清單（容量、故障演練、相依服務、漸進式發布、監控監控本身）
- [Mercari Production Readiness Checklist](https://github.com/mercari/production-readiness-checklist)：GitHub 上公開、實際在用的版本，分成 Maintainability / Observability / Reliability / Security / Data Storage，依 SLO 分等級決定要做到哪些
- [AWS Well-Architected Generative AI Lens](https://docs.aws.amazon.com/wellarchitected/latest/generative-ai-lens/generative-ai-lens.html)：六根支柱套在生成式 AI 的生命週期上（選模型、prompt、整合、部署、持續改進）
- [OWASP Top 10 for LLM Applications 2025](https://genai.owasp.org/llm-top-10/)、[OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)：LLM 和 agent 特有的資安風險；表格裡的 `LLM01` 這類編號指的是這份

```
① 能不能上線    ② 怎麼上線      ③ 跑起來之後     ④ 出事的時候     ⑤ 長期活下去
  Ready           Release          Run              Respond          Evolve
  測試/資安/設定   CI/CD/部署/回退   觀測/容量/成本    告警/除錯/復原    升級/資料/文件
```

狀態：✅ 有證據　⚠️ 部分做到　❌ 還沒有

---

## ① 能不能上線（Ready）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 分層與架構守護 | ✅ | [ArchitectureTest](../backend/src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java)：用 ArchUnit 把分層規則寫成測試，違反就紅燈 |
| 整合測試用真的資料庫 | ✅ | Testcontainers 起真的 PostgreSQL；不用 H2 的理由寫在 [build.gradle](../backend/build.gradle) |
| API 契約文件 | ✅ | [OpenApiDocumentationTest](../backend/src/test/java/io/github/andersonchen/talkcal/OpenApiDocumentationTest.java) |
| 整個應用接起來的系統測試 | ❌ | 以前有一支對 `/api/chat` 起整個應用、打真 HTTP、接真模型的系統測試，隨著那個端點一起拿掉了。AI 助理目前只有元件層級的整合測試（`CalendarAssistantConversationTest`），還沒有從 HTTP 一路打到模型和資料庫的那一支 |
| 前端測試 | ❌ | [web/package.json](../web/package.json) 沒有任何測試工具，目前只有 `vue-tsc` 型別檢查 |
| AI 品質回歸（eval） | ❌ | 改了 prompt 或換模型之後，沒辦法知道有沒有變笨。需要一組固定題目加自動評分 |
| 認證與授權 | ❌ | 沒有登入；知道 conversation id 就能讀那段對話 |
| 濫用防護（rate limit） | ❌ | `/api/calendar/assistant` 每次都占用 GPU（一輪還可能叫好幾次模型），一個人就能把服務塞滿（`LLM10`）。目前只有單次請求的上限：`max-tokens=2048` 擋住一次生成暴走，但沒擋住請求次數 |
| Agent 權限範圍 | ✅ | [CalendarTools](../backend/src/main/java/io/github/andersonchen/talkcal/calendar/adapter/in/assistant/CalendarTools.java) 只給模型三個工具：查行程、找空檔、提議行程。提議不會存檔，一定要使用者在畫面上按確認；agent 沒有刪除或修改的工具（`LLM06` Excessive Agency：只給完成任務需要的動作，寫入要人確認） |
| Prompt injection | ⚠️ | 權限收窄之後，被注入最多只能讓它提議錯的行程，或在回答裡說錯話。剩下的風險是間接注入（`LLM01`）：`list_events` 會把行程標題、備註原封不動交給模型，有人把指令寫進行程標題就會被模型讀到。沒有對這類輸入做標記或過濾，也沒有測試 |
| 模型輸出當成不可信資料 | ✅ | 前端只用 `{{ }}` 顯示模型回答，沒有 `v-html`，模型吐出 HTML 或 script 也只會被當文字（`LLM05` Improper Output Handling） |
| 外部相依的逾時與重試 | ✅ | [application.properties](../backend/src/main/resources/application.properties)：模型呼叫 2 分鐘逾時；SDK 預設重試關掉（`max-retries=0`），理由寫在設定旁邊：本機模型塞爆時重試只會更塞 |
| 祕密管理 | ⚠️ | [application.properties](../backend/src/main/resources/application.properties) 直接寫著資料庫密碼。開發用可以，上線要改從環境變數或 secrets 注入 |
| Container 加固 | ✅ | [Dockerfile](../backend/Dockerfile)：兩段式 build、不跑 root、exec 形式讓 JVM 收得到 SIGTERM |
| 相依套件與映像檔掃描 | ❌ | 沒有 Trivy、OWASP dependency-check |
| 第三方映像檔與模型檔的來源 | ✅ | [compose](../deploy/compose.yaml) 裡的 postgres、llama.cpp 映像檔都釘死版本，不用 `latest`；模型檔複製進 volume 時比對 SHA256，對不上模型服務就不啟動（`LLM03` Supply Chain） |

## ② 怎麼上線（Release）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 原始碼放在公開平台 | ✅ | [GitHub：Anderson-chen/talkcal](https://github.com/Anderson-chen/talkcal)，公開 |
| CI | ✅ | [ci/](../ci/README.md)：每次 push、PR 在乾淨的容器裡跑後端測試和前端打包，本機 pre-push 跑同一支。GitHub 上約 2 分 20 秒、本機有快取 30 秒。GitHub 沒有 GPU，模型測試只在本機會真的跑，跳過的會列在結果頁 |
| 一鍵部署 | ✅ | `./gradlew deploy`，見 [deploy/README.md](../deploy/README.md) |
| DB migration | ✅ | [Flyway V1–V8](../backend/src/main/resources/db/migration) |
| 零停機的 schema 變更 | ❌ | 沒演練過 expand/contract（改欄位時新舊版本會同時在線） |
| Graceful shutdown | ⚠️ | JVM 收得到 SIGTERM，但等待時間對不上：LLM 呼叫的 timeout 是 2 分鐘，Spring 每個關機階段預設只等 30 秒，[compose](../deploy/compose.yaml) 沒設 `stop_grace_period`，Docker 只等 10 秒就送 SIGKILL。**推測**每次部署都會砍斷正在回答的請求，待用 k6 邊壓邊部署驗證 |
| Rollback | ❌ | app 映像檔固定叫 `talkcal/app:local`，沒有版本 tag，退不回上一版 |
| 漸進式發布（canary） | ❌ | 只有一個 app 實例，新版一上就是全部流量。Google 和 Mercari 的清單都要求先放一小部分流量、看指標沒問題再全開；單機環境做不到，至少要先有 rollback |
| Hotfix 流程 | ❌ | 沒有分支策略和 release tag |

## ③ 跑起來之後（Run）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 指標、log、trace 三種訊號 | ✅ | [ops/](../ops/README.md)：Alloy → Mimir / Loki / Tempo → Grafana，4 個 dashboard |
| 結構化 log 帶 trace id | ✅ | 容器裡用 ECS 格式；[AccessLogTraceIdTest](../backend/src/test/java/io/github/andersonchen/talkcal/app/observability/AccessLogTraceIdTest.java) |
| 健康檢查 | ✅ | compose healthcheck 打 `/actuator/health`。只有一種檢查，還沒分成 liveness（程序卡死要重啟）和 readiness（暫時不收流量），搬上 k8s 時要分開 |
| Actuator 暴露面 | ⚠️ | 只開 `health`、`prometheus`，但跟 API 同一個埠，沒有分開只給內網 |
| 壓測與容量 | ✅ | [perf/](../perf/README.md)：smoke、load、knee，用 Little's Law 驗算。k6 的指標還沒接進 Grafana，壓測曲線不能跟 app、模型的指標疊在同一張圖上看 |
| SLO | ❌ | 沒定義什麼叫「夠好」；dashboard 能看，但沒有可以比較的標準 |
| 成本 | ❌ | 沒記錄 token 用量，沒算過自架 GPU 和雲端 API 在多少用量時交叉 |
| 產品指標 | ❌ | 知道系統健不健康，不知道功能有沒有人用（例如助理提議的行程被採納幾成） |
| 資料保存期限 | ✅ | [AssistantMemoryCleanup](../backend/src/main/java/io/github/andersonchen/talkcal/app/job/AssistantMemoryCleanup.java)：30 天沒說話的對話每天清掉 |
| Trace 取樣率 | ⚠️ | 目前 100% 取樣。本機沒問題，上線流量大時要調整 |

## ④ 出事的時候（Respond）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 告警 | ❌ | Grafana 只有 dashboard，沒有 alert rule。沒人盯就等於沒有 |
| 監控本身掛掉 | ❌ | Alloy 或 Mimir 停了，dashboard 只會變成一片空白，不會有人知道（Google 清單的 “monitoring the monitoring”）。有了告警之後，要加一條「指標停止進來」的告警 |
| Runbook | ❌ | 告警響了之後要做什麼，沒有寫下來 |
| Postmortem | ❌ | 還沒有任何一份事故檢討 |
| 從使用者回報追到 trace | ❌ | 前端沒顯示 trace id；使用者說「剛剛那次怪怪的」時找不到是哪一次 |
| 失敗時的使用者體驗 | ⚠️ | 待確認 LLM 掛掉或超時的時候，前端顯示什麼 |
| 降級與熔斷 | ❌ | llama-server 掛了，助理就跟著掛，除了 timeout 沒有別的保護 |
| 備份與還原 | ❌ | PostgreSQL 沒有備份，也沒演練過還原。沒還原過的備份不算備份 |

## ⑤ 長期活下去（Evolve）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 根目錄 README | ✅ | [README.md](../README.md)：產品是什麼、作品集想展示什麼、架構圖、怎麼跑、文件地圖 |
| 決策紀錄（ADR） | ⚠️ | 理由都寫在程式碼註解和各目錄的 README，但沒有集中的地方說明「為什麼選 Spring AI、llama.cpp、JDBC」 |
| 相依套件升級 | ❌ | 沒有 Renovate 或 Dependabot |
| 並行修改 | ⚠️ | 行程目前只能新增和刪除、沒有修改的 API，所以還沒有「兩個分頁互相覆蓋」的問題；以後加修改功能時，`calendar_event` 要先加 version 欄位。助理的對話記憶是整段刪掉重插，同一段對話同時送兩句，晚存的會蓋掉早的（[V7](../backend/src/main/resources/db/migration/V7__create_calendar_assistant_message.sql) 寫明接受這個代價） |
| 冪等寫入 | ❌ | 按兩次「加入」或 agent 重試，可能多出一筆一樣的行程 |
| 時區 | ✅ | [V2](../backend/src/main/resources/db/migration/V2__create_calendar_events.sql) 寫了為什麼用不帶時區的 `TIMESTAMP` |
| 隱私 | ❌ | 沒確認對話內容會不會進 Loki 和 Tempo（`LLM02` Sensitive Information Disclosure；Mercari 清單的 “Non-sensitive log”），也沒有使用者要求刪除時的流程 |
| 授權（License） | ❌ | 沒整理 Qwen3 和前端套件的授權 |
| 資料歸檔 | ❌ | 舊資料是直接刪掉；還沒有「壓縮後放到物件儲存」的流程 |

---

## 接下來的順序

| 順序 | 做什麼 | 為什麼排在這裡 |
|---|---|---|
| ~~1~~ | ~~推上 GitHub、寫根目錄 README、補 CI~~ ✅ | 其他東西都要靠這三樣才看得到、才有自動把關 |
| 2 | 先把 k6 接進 Grafana，再驗證並修好 graceful shutdown，寫成第一份 postmortem | 一個真實的問題同時練到部署、k6、事故處理；k6 先接上 Grafana，邊壓邊部署時失敗尖峰、錯誤率、部署時間點才會在同一張圖上，證據一次到位 |
| 3 | SLO、告警、runbook | 讓 `ops/` 從「看得到」變成「出事會叫人」 |
| 4 | AI eval | LLM 產品特有的品質把關 |
| 5 | Rate limit、間接 prompt injection 的防線和測試 | 資安。agent 的權限已經收窄，剩下的是「塞爆 GPU」和「行程內容裡藏指令」 |
| 6 | 備份還原演練、舊資料歸檔到物件儲存 | 資料生命週期 |
