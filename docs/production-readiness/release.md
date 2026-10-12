# ② 怎麼上線（Release）

[← 回上線準備清單](../production-readiness.md)

<a id="github"></a>
### 原始碼放在 GitHub ✅

[GitHub：Anderson-chen/talkcal](https://github.com/Anderson-chen/talkcal)，公開。2026-10-08 從私人改回公開，為的是能開 branch protection。`docs/` 底下的清單和它的 git 歷史也都看得到。repo 裡沒有真的祕密，資料庫密碼和 API key 都是本機用的假值。

<a id="ci"></a>
### CI ✅

[ci/](../../ci/README.md)：每次 push 和 PR 都在乾淨的容器裡跑後端測試和前端打包，本機的 pre-push 跑的也是同一支。在 GitHub 上約 2 分 20 秒，本機有快取約 30 秒。GitHub 沒有 GPU，所以模型測試只有在本機會真的跑，被跳過的會列在結果頁。

<a id="deploy"></a>
### 一鍵部署 ✅

`./gradlew deploy`，見 [deploy/README.md](../../deploy/README.md)。

<a id="migration"></a>
### DB migration ✅

[Flyway V1–V8](../../backend/src/main/resources/db/migration)

<a id="zero-downtime-schema"></a>
### 零停機的 schema 變更 ❌

沒演練過 expand/contract。改欄位的時候，新舊兩個版本會同時在線，schema 要兩邊都能用。

<a id="graceful-shutdown"></a>
### Graceful shutdown ❌（部分做到）

JVM 收得到 SIGTERM，但三個等待時間對不上：LLM 呼叫的逾時是 2 分鐘，Spring 每個關機階段預設只等 30 秒，[compose](../../deploy/compose.yaml) 又沒設 `stop_grace_period`，Docker 只等 10 秒就送 SIGKILL。**推測**每次部署都會砍斷正在回答的請求，還要用 k6 邊壓邊部署來驗證。

<a id="rollback"></a>
### Rollback ❌

app 映像檔固定叫 `talkcal/app:local`，沒有版本 tag，退不回上一版。

<a id="canary"></a>
### 漸進式發布（canary） ❌

只有一個 app 實例，新版一上線就承接全部流量。Google 和 Mercari 的清單都要求先放一小部分流量進來，看指標沒問題再全開。單機環境做不到，至少要先有 rollback。

<a id="jira"></a>
### 需求與待辦事項追蹤（Jira） ❌

**現況**：沒有任何 issue。待辦事項散在各份文件裡（例如 [README](../../README.md) 裡的「改成可設定的路徑是待辦事項」），這份清單裡的 ❌ 也只是文字。一個改動「為什麼要做、做到哪裡、什麼時候上線」，只能從 commit 訊息猜。

**要補的流程**：每個改動都從一張 issue 開始，issue key 一路帶到上線。

```
Jira issue      分支                 commit / PR                  CI        合併、部署       Jira 狀態
TALK-42 ──────▶ TALK-42-graceful ──▶ 「TALK-42 加長 stop_grace」 ──▶ 綠燈 ──▶ master、deploy ──▶ To Do → In Progress → In Review → Done
```

| 步驟 | 做法 | 為什麼 |
|---|---|---|
| 開 issue | 這份清單的五段各開一個 Epic，每個 ❌ 是一張 Story 或 Task；告警、postmortem 找到的問題開成 Bug | 待辦只放一個地方，文件裡只連到 issue |
| 開分支 | 分支名稱以 issue key 開頭，例如 `TALK-42-graceful-shutdown` | 裝了 GitHub for Jira 之後，Jira 會自動把分支、commit、PR 掛回 issue |
| commit 和 PR | 訊息和標題開頭帶 `TALK-42` | 半年後看到一行程式，可以追回當初的需求和討論 |
| 狀態流轉 | 開 PR 時轉成 In Review，合併並部署之後轉成 Done（用 Jira Automation 做，不靠人記得） | issue 的狀態就是真實的狀態 |
| Hotfix | 開最高優先的 Bug，照上面同一條流程走，只是插隊 | 緊急不等於不留紀錄 |

**做完的證據**：Jira board 的截圖或公開連結、issue 上自動掛著的 PR 和部署紀錄。issue 從開到部署的時間，就是 [DORA](#dora) 的「變更前置時間」，有了 Jira 才量得出來。

**要先決定的事**：這條流程要靠 PR 才完整，所以跟 [Branch protection](#branch-protection) 選 B 是同一件事。

<a id="branching"></a>
### 分支策略 ❌（部分做到）

實際的做法是 trunk-based：每個 commit 直接進 `master`，pre-push 先跑完整的 CI 才推得出去。但這沒有寫成規則，也沒有 PR，變更只留下 commit，沒有審查紀錄。

**不選 Git Flow**：它的 `develop`、`release/*`、`hotfix/*` 是為了「同時維護好幾個發行版」設計的，例如桌面軟體或 SDK。連作者自己都在 2020 年補註說，持續部署的服務應該用更簡單的流程，而 talkcal 永遠只有線上那一版。

<a id="branch-protection"></a>
### Branch protection ❌

現在擋壞程式的只有本機的 pre-push，用 `git push --no-verify` 就能跳過，換一台沒設 hook 的電腦也擋不到。branch protection 是 GitHub 伺服器那一端的規則，誰都跳不過。私人 repo 在免費方案開不了，所以 repo 改回了公開。

**還沒決定**要哪一種：

- A. 只禁止 force push 和刪除 `master`，照樣直接推。
- B. 一定要發 PR、CI 綠燈才能合併。

傾向 B：CI 會從「可以跳過」變成「強制」，PR 也順便成為 AI 改動的審查紀錄（見 [AI 改動的人工審查](#ai-review)）和 [Jira](#jira) 流程的一環。代價是每次改動都要多開一個 PR。

<a id="lint"></a>
### 程式碼格式與 lint ❌

後端沒有 Spotless、Checkstyle，前端沒有 ESLint、Prettier，也沒有 SpotBugs、Error Prone 這類靜態分析。CI 只擋編譯錯誤、測試失敗和型別錯誤。AI 寫的程式風格容易飄，formatter 能自動拉齊。

<a id="coverage"></a>
### 測試覆蓋率 ❌

沒有 JaCoCo，不知道哪些程式沒被測到。只打算拿來找漏測的地方，不設門檻。

<a id="staging"></a>
### 環境分層（staging） ❌

只有本機開發和 docker 兩種設定，新版直接上「線上」，沒有先放在一個跟線上一樣的環境驗證。單機很難真的做出來，先列成已知限制。

<a id="release-tag"></a>
### 版本與 release tag ❌

沒有 git tag，映像檔也沒有版本號，所以說不出「線上現在跑的是哪個 commit」。rollback 和 hotfix 都要從這裡起步。

<a id="hotfix"></a>
### Hotfix 流程 ❌

還沒寫下來。trunk-based 的做法是：修在 `master`、照常過 CI、部署。如果 `master` 上已經有還不能上線的東西，就從線上那一版的 tag 開一條短命分支，修好上線之後再 cherry-pick 回 `master`。重點是「緊急」不等於「跳過 CI」：CI 要夠快（現在 GitHub 約 2 分 20 秒），才不會讓人想跳過。

<a id="ai-guardrails"></a>
### 用 AI 寫程式的把關 ✅

AI（Claude Code）寫的程式跟人寫的過同一道關，而且這道關不靠人記得：[ArchitectureTest](../../backend/src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java) 擋分層違規，[CI](../../ci/README.md) 擋測試和打包失敗，pre-push 讓壞的程式推不出去。AI 最常犯的錯（亂 import、跨層呼叫、改壞別的地方）都會變成紅燈。

<a id="agents-md"></a>
### 給 AI 的專案規則 ❌

repo 裡沒有 `AGENTS.md` 或 `CLAUDE.md`。寫法的慣例（註解寫原因、一次只長一個 class、選型理由寫進 README）只存在個人的 AI 設定裡，換一台電腦、換一個工具或換一個人就沒有了。業界的做法是把這些寫成跟著 repo 走的規則檔。目前決定先不做（2026-10-08）。

<a id="ai-review"></a>
### AI 改動的人工審查 ❌

AI 的修改直接 commit 到 `master`，沒有經過 PR，審查只發生在對話當下，沒有留下紀錄。可以改成 AI 一律開分支、發 PR，CI 綠燈、自己看過 diff 之後再合併。

<a id="dora"></a>
### 交付指標（DORA） ❌

沒有量部署頻率、變更前置時間、變更失敗率和復原時間。DORA 2025 的研究指出，用了 AI 之後產出變快，但交付的穩定度常常下降；不量就不知道自己是哪一種。變更前置時間要有 [Jira](#jira) 的 issue 時間點才量得出來。
