# 上線準備清單（Production Readiness）

talkcal 不只要「能跑」，還要能回答：**上線之後要顧慮哪些事？哪些做了、哪些還沒？**
這份清單就是那個答案。每補完一項就把狀態改掉並附上證據的連結；還沒做的也照實列出來，已知的缺口比假裝沒有缺口好。

最後盤點：2026-10-07

## 怎麼分類

面向一個一個列很容易漏，所以照產品的生命週期分成五段（思路來自 Google SRE 的 Production Readiness Review
和 AWS Well-Architected）。被問到「上線要注意什麼」，照這五段走一輪就不會漏掉一大塊：

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
| 濫用防護（rate limit） | ❌ | `/api/calendar/assistant` 每次都占用 GPU（一輪還可能叫好幾次模型），一個人就能把服務塞滿 |
| Prompt injection | ❌ | agent 能新增、刪除行程，破壞性操作沒有額外防線 |
| 祕密管理 | ⚠️ | [application.properties](../backend/src/main/resources/application.properties) 直接寫著資料庫密碼。開發用可以，上線要改從環境變數或 secrets 注入 |
| Container 加固 | ✅ | [Dockerfile](../backend/Dockerfile)：兩段式 build、不跑 root、exec 形式讓 JVM 收得到 SIGTERM |
| 相依套件與映像檔掃描 | ❌ | 沒有 Trivy、OWASP dependency-check |

## ② 怎麼上線（Release）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 原始碼放在公開平台 | ✅ | [GitHub：Anderson-chen/talkcal](https://github.com/Anderson-chen/talkcal)，公開 |
| CI | ✅ | [ci/](../ci/README.md)：每次 push、PR 在乾淨的容器裡跑後端測試和前端打包，本機 pre-push 跑同一支。GitHub 上約 2 分 20 秒、本機有快取 30 秒。GitHub 沒有 GPU，模型測試只在本機會真的跑，跳過的會列在結果頁 |
| 一鍵部署 | ✅ | `./gradlew deploy`，見 [deploy/README.md](../deploy/README.md) |
| DB migration | ✅ | [Flyway V1–V7](../backend/src/main/resources/db/migration) |
| 零停機的 schema 變更 | ❌ | 沒演練過 expand/contract（改欄位時新舊版本會同時在線） |
| Graceful shutdown | ⚠️ | JVM 收得到 SIGTERM，但等待時間對不上：LLM 呼叫的 timeout 是 2 分鐘，Spring 每個關機階段預設只等 30 秒，[compose](../deploy/compose.yaml) 沒設 `stop_grace_period`，Docker 只等 10 秒就送 SIGKILL。**推測**每次部署都會砍斷正在回答的請求，待用 k6 邊壓邊部署驗證 |
| Rollback | ❌ | 映像檔沒有版本 tag，退不回上一版 |
| Hotfix 流程 | ❌ | 沒有分支策略和 release tag |

## ③ 跑起來之後（Run）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 指標、log、trace 三種訊號 | ✅ | [ops/](../ops/README.md)：Alloy → Mimir / Loki / Tempo → Grafana，4 個 dashboard |
| 結構化 log 帶 trace id | ✅ | 容器裡用 ECS 格式；[AccessLogTraceIdTest](../backend/src/test/java/io/github/andersonchen/talkcal/AccessLogTraceIdTest.java) |
| 健康檢查 | ✅ | compose healthcheck 打 `/actuator/health` |
| Actuator 暴露面 | ⚠️ | 只開 `health`、`prometheus`，但跟 API 同一個埠，沒有分開只給內網 |
| 壓測與容量 | ✅ | [perf/](../perf/README.md)：smoke、load、knee，用 Little's Law 驗算 |
| SLO | ❌ | 沒定義什麼叫「夠好」；dashboard 能看，但沒有可以比較的標準 |
| 成本 | ❌ | 沒記錄 token 用量，沒算過自架 GPU 和雲端 API 在多少用量時交叉 |
| 產品指標 | ❌ | 知道系統健不健康，不知道功能有沒有人用（例如助理提議的行程被採納幾成） |
| 資料保存期限 | ✅ | [AssistantMemoryCleanup](../backend/src/main/java/io/github/andersonchen/talkcal/AssistantMemoryCleanup.java)：30 天沒說話的對話每天清掉 |
| Trace 取樣率 | ⚠️ | 目前 100% 取樣。本機沒問題，上線流量大時要調整 |

## ④ 出事的時候（Respond）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 告警 | ❌ | Grafana 只有 dashboard，沒有 alert rule。沒人盯就等於沒有 |
| Runbook | ❌ | 告警響了之後要做什麼，沒有寫下來 |
| Postmortem | ❌ | 還沒有任何一份事故檢討 |
| 從使用者回報追到 trace | ❌ | 前端沒顯示 trace id；使用者說「剛剛那次怪怪的」時找不到是哪一次 |
| 失敗時的使用者體驗 | ⚠️ | 待確認 LLM 掛掉或超時的時候，前端顯示什麼 |
| 降級與熔斷 | ❌ | llama-server 掛了，助理就跟著掛，除了 timeout 沒有別的保護 |
| 備份與還原 | ❌ | PostgreSQL 沒有備份，也沒演練過還原。沒還原過的備份不算備份 |

## ⑤ 長期活下去（Evolve）

| 面向 | 狀態 | 證據或缺口 |
|---|---|---|
| 根目錄 README | ❌ | 打開 repo 第一眼沒有說明 |
| 決策紀錄（ADR） | ⚠️ | 理由都寫在程式碼註解和各目錄的 README，但沒有集中的地方說明「為什麼選 Spring AI、llama.cpp、JDBC」 |
| 相依套件升級 | ❌ | 沒有 Renovate 或 Dependabot |
| 並行修改 | ❌ | `calendar_event` 沒有 version 欄位，兩個分頁同時改會互相覆蓋；助理的對話記憶是整段刪掉重插，同一段對話同時兩句，晚存的蓋掉早的（[V7](../backend/src/main/resources/db/migration/V7__create_calendar_assistant_message.sql) 寫明接受這個代價） |
| 冪等寫入 | ❌ | 按兩次「加入」或 agent 重試，可能多出一筆一樣的行程 |
| 時區 | ✅ | [V2](../backend/src/main/resources/db/migration/V2__create_calendar_events.sql) 寫了為什麼用不帶時區的 `TIMESTAMP` |
| 隱私 | ❌ | 沒確認對話內容會不會進 Loki 和 Tempo，也沒有使用者要求刪除時的流程 |
| 授權（License） | ❌ | 沒整理 Qwen3 和前端套件的授權 |
| 資料歸檔 | ❌ | 舊資料是直接刪掉；還沒有「壓縮後放到物件儲存」的流程 |

---

## 接下來的順序

| 順序 | 做什麼 | 為什麼排在這裡 |
|---|---|---|
| 1 | 推上 GitHub、寫根目錄 README、補 CI | 其他東西都要靠這三樣才看得到、才有自動把關 |
| 2 | 驗證並修好 graceful shutdown，寫成第一份 postmortem | 一個真實的問題同時練到部署、k6、事故處理 |
| 3 | SLO、告警、runbook | 讓 `ops/` 從「看得到」變成「出事會叫人」 |
| 4 | AI eval | LLM 產品特有的品質把關 |
| 5 | Rate limit、prompt injection 防線 | 資安 |
| 6 | 備份還原演練、舊資料歸檔到物件儲存 | 資料生命週期 |
