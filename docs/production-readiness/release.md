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
### Graceful shutdown ✅

三個等待時間對齊了：一次叫模型最多 2 分鐘，Spring 關機時等手上的請求最多 2 分鐘（[application.properties](../../backend/src/main/resources/application.properties)），Docker 等 2 分 30 秒才送 SIGKILL（[compose](../../deploy/compose.yaml) 的 `stop_grace_period`）。

以前 Docker 只等 10 秒。用 k6 邊壓邊部署驗證過：部署當下有慢的請求時，改之前被 SIGKILL（結束碼 137），處理中的 9 個請求被砍斷；改之後等它們做完才結束（143），0 個被砍。經過寫在 [postmortem](../postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)。

還沒解決的是部署期間的停機：只有一個 app、先關舊的再開新的，停機那十幾秒進來的請求一樣會失敗，見[漸進式發布](#canary)。

<a id="rollback"></a>
### Rollback ❌

app 映像檔固定叫 `talkcal/app:local`，沒有版本 tag，退不回上一版。

<a id="canary"></a>
### 漸進式發布（canary） ❌

只有一個 app 實例，新版一上線就承接全部流量；部署也是先關舊的、再開新的，中間有一段沒人接的空窗（2026-10-12 量到 15–25 秒，見 [postmortem](../postmortems/2026-10-12-deploy-cuts-in-flight-requests.md)）。第一步是零停機部署：起兩個 app，先起新的、健康了再關舊的。Google 和 Mercari 的清單都要求先放一小部分流量進來，看指標沒問題再全開。單機環境做不到，至少要先有 rollback。

<a id="issues"></a>
### 需求與待辦事項追蹤（GitHub Issues） ✅

每個改動都從一張 issue 開始，PR 用 `Closes #N` 連回去，合併時 issue 自動關閉。

```
issue #21 ──▶ 分支 21-issue-workflow ──▶ PR「Closes #21」──▶ CI 綠燈 ──▶ 合併進 master ──▶ #21 自動關閉
 Todo            In Progress                In Review                                      Done
```

| 步驟 | 做法 | 為什麼 |
|---|---|---|
| 開 issue | 這份清單的五段各是一張父 issue（#8、#17、#30、#38、#46），每個 ❌ 是底下的 sub-issue；告警、postmortem 找到的問題也開成 issue | 待辦只放一個地方。父 issue 上的進度條就是這一段做完幾成 |
| 開分支 | 在 issue 頁按「Create a branch」，名稱是 `編號-描述`，例如 `21-issue-workflow` | 分支直接掛在 issue 的 Development 欄 |
| 開 PR | 描述寫 `Closes #21`（[PR 範本](../../.github/pull_request_template.md)已經放好） | 合併時 GitHub 自動關 issue；半年後從 `git blame` → commit → PR → issue，一路點得回當初為什麼做 |
| 擋住沒連 issue 的 PR | [pr-issue.yml](../../.github/workflows/pr-issue.yml) 問 GitHub「這個 PR 合併時會關哪些 issue」，一個都沒有就紅燈，並設成 [Branch protection](#branch-protection) 的必過檢查 | 連結一斷，上面全部失效；靠人記得一定會漏 |
| 狀態流轉 | Projects 看板的內建 workflow：issue 開了加進看板、PR 連上轉 In Review、關閉轉 Done | issue 的狀態就是真實的狀態，不靠人去拖卡片 |
| Hotfix | 一樣先開 issue，照同一條流程走，只是插隊 | 緊急不等於不留紀錄 |

issue 開立到關閉的時間，就是 [DORA](#dora) 的「變更前置時間」的起點和終點。

**為什麼不用 Jira**（2026-10-12）：原本規劃用 Jira（issue key `TALKCAL-21` 寫進分支和 PR 標題，靠 GitHub for Jira 和 Jira Automation 串起來），Jira site 也建好、issue 也匯入了。改成 GitHub Issues 是因為：

- 一個人開發，Jira 多出來的能力（跨團隊權限、複雜的 workflow、報表）用不到
- 連動大多是 GitHub 原生的，不用裝 app、不用維護 Automation 規則，少一個外部系統可能壞掉
- repo 是公開的，看的人點進來就看得到 issue 和 PR 怎麼串；Jira board 要另外開權限

流程本身（issue → 分支 → PR → CI → 合併 → 自動關閉）跟 Jira 版一樣，團隊換成 Jira 時，換掉的只是 key 的格式和 [pr-issue.yml](../../.github/workflows/pr-issue.yml) 的檢查方式。

<a id="branching"></a>
### 分支策略 ✅

做法是 trunk-based，2026-10-12 起加上 PR：

1. 從 `master` 開短命分支，名稱是 `issue 編號-描述`，例如 `21-issue-workflow`（還沒有 issue 之前，用簡短的英文描述）
2. 在分支上 commit，push 前 pre-push 先在本機跑一次 CI
3. 開 PR 到 `master`，GitHub 上的 CI 綠燈、自己看過 diff 才合併
4. 合併後刪掉分支，分支不活過幾天

以前是每個 commit 直接進 `master`，沒有審查紀錄。現在由 [Branch protection](#branch-protection) 在 GitHub 上強制，不走這條路就進不了 `master`。

**不選 Git Flow**：它的 `develop`、`release/*`、`hotfix/*` 是為了「同時維護好幾個發行版」設計的，例如桌面軟體或 SDK。連作者自己都在 2020 年補註說，持續部署的服務應該用更簡單的流程，而 talkcal 永遠只有線上那一版。

<a id="branch-protection"></a>
### Branch protection ✅

以前擋壞程式的只有本機的 pre-push，用 `git push --no-verify` 就能跳過，換一台沒設 hook 的電腦也擋不到。branch protection 是 GitHub 伺服器那一端的規則，誰都跳不過。私人 repo 在免費方案開不了，所以 repo 改回了公開。

考慮過兩種：

- A. 只禁止 force push 和刪除 `master`，照樣直接推。
- B. 一定要發 PR、CI 綠燈才能合併。

**決定選 B**（2026-10-12）：CI 會從「可以跳過」變成「強制」，PR 也順便成為 AI 改動的審查紀錄（見 [AI 改動的人工審查](#ai-review)）和 [issue 流程](#issues)的一環。代價是每次改動都要多開一個 PR。A 只防得了手滑，防不了沒過 CI 的程式碼進到 `master`。

`master` 要套的規則：

| 規則 | 設定 | 為什麼 |
|---|---|---|
| 合併前要有 PR | 要，不用別人核准 | 只有一個人開發，GitHub 不讓作者核准自己的 PR；要的是「一定經過 PR」，不是「一定有人核准」 |
| 合併前 CI 要綠燈 | 要，檢查項目是 `checks`（[ci.yml](../../.github/workflows/ci.yml) 裡的 job），分支要跟 `master` 同步 | 沒跑過最新 `master` 的組合，綠燈不算數 |
| 合併前要連到 issue | 要，檢查項目是 `pr-issue`（[pr-issue.yml](../../.github/workflows/pr-issue.yml)），2026-10-12 加上 | 沒連 issue，[issue 流程](#issues)的自動關閉和追溯都會斷 |
| 禁止 force push、禁止刪除 | 要 | 歷史不能被改寫 |
| 管理員也要遵守 | 要 | 不然自己身為管理員，照樣能直推，等於沒設。真的緊急時改走 [Hotfix 流程](#hotfix)，一樣開 PR |

2026-10-12 已在 GitHub 上套用（ruleset `protect-master`）。證據：[Anderson-chen/talkcal#1](https://github.com/Anderson-chen/talkcal/pull/1) 是套用之後第一個照規則合併的 PR，CI 綠燈之前合併按鈕按不下去。

規則只存在 GitHub 的設定裡，repo 裡沒有對應的檔案；要看目前的內容，到 repo 的 Settings → Rules → Rulesets，或用 `gh api repos/Anderson-chen/talkcal/rules/branches/master`。

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

沒有量部署頻率、變更前置時間、變更失敗率和復原時間。DORA 2025 的研究指出，用了 AI 之後產出變快，但交付的穩定度常常下降；不量就不知道自己是哪一種。變更前置時間要有 [issue](#issues) 的開立和關閉時間點才量得出來。
