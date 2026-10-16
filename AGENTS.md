# AGENTS.md

在這個 repo 寫程式的規則，人和任何 AI 工具（Codex、Cursor、Copilot、Gemini CLI、Claude Code…）都照這份。
專案是什麼、怎麼跑，看 [README.md](README.md)；這裡只放「寫法」和「流程」，每條附一句理由。

規則寫在這裡、不寫在個人的 AI 設定裡：個人設定換一台電腦、換一個工具或換一個人就沒了，這份跟著 repo 走。
Claude Code 專用的補充在 [CLAUDE.md](CLAUDE.md)，它開頭用 `@AGENTS.md` 引入這份，所以兩份不會對不上。

## 語言

- 回覆、介面文字、文件、註解、commit 訊息、issue、PR 都用繁體中文；程式識別字、指令、commit 類型用英文。
  理由：作品集的讀者和使用者都讀中文，同一份東西混兩種語言讀起來會斷。

## 指令

都在 repo 根目錄下打：

| 要做什麼 | 指令 |
|---|---|
| 跟 CI 一模一樣的整輪檢查 | `ci/run.sh` |
| 後端單元測試、架構規則 | `cd backend && ./gradlew test` |
| 後端整合測試（真的 PostgreSQL、模型） | `cd backend && ./gradlew integrationTest` |
| 前端單元測試、E2E | `npm --prefix web test`、`npm --prefix web run e2e` |
| 格式與 lint（#22 合併後才有） | 檢查 `ci/format.sh`、自動排好 `ci/format.sh apply`；前端 `npm --prefix web run lint`、`format:check` |

- 格式以工具為準，這份不另外寫縮排、換行的規定。理由：工具會擋、規則只有一份；寫在這裡會跟工具的設定對不上。
- pre-push hook 跑的就是 `ci/run.sh`（`git config core.hooksPath .githooks` 啟用）。理由：GitHub 上沒有 GPU，模型相關的測試只有本機會真的跑。

## 寫程式

- **註解寫「為什麼」，不寫「做什麼」。** 理由：做什麼看程式碼就知道，會丟掉的是當初為什麼這樣選、排除了什麼。
- **一次只長一個 class，每個成員、每條規則附原因。** 不一次提出整套多層架構或幾十個檔案。
  理由：小步才看得懂每一步、也才審得動；說不出原因的成員通常不該存在。
- **選型理由寫進 repo**：用什麼、為什麼不用另一個，寫在該目錄的 README（例如 `ci/README.md`、`ops/README.md`）或程式碼註解。
  理由：理由只存在對話裡，下一個人只看得到結果，會把刻意的選擇當成疏忽改掉。
- **後端分層照 [ArchitectureTest](backend/src/test/java/io/github/andersonchen/talkcal/ArchitectureTest.java)**（ArchUnit，六角架構）：
  依賴只能由外往內；`application`（core）不認識 Spring、Jackson；只有組裝根 `app.config`、`app.job` 認識 adapter 的具體類別；模組之間互不 import。
  理由：規則寫成測試，違反就紅燈，不靠人記得。要改規則就改測試，並在 `because(...)` 寫上新的理由。

## 驗證

- **每道檢查都要做反例**：刻意造出它該抓的東西，證明會被擋（例如 commit 一個假 token 給 gitleaks、放有漏洞的 jar 給 Trivy、連打 API 看 429）；
  再證明正常的東西沒被誤擋。結果寫進 PR 的驗證段落。理由：綠燈只代表「跑了沒出錯」，沒有反例就不知道這道關卡有沒有在擋。
- **寫或改 Playwright E2E（`web/e2e/`）之前，先把前端開起來、用瀏覽器看實際的元素和 role、label**，再寫選擇器。
  理由：只讀原始碼會猜錯畫面上有什麼（月曆每一格都有一顆「新增行程」，手機版還有兩個同名按鈕）；只看頁面又不懂需求，會把現有的 bug 當成正確行為，兩邊都要看。
- 沒跑的測試、沒驗到的部分，在 PR 裡寫出來。理由：沒寫就會被當成驗過了。

## 產品角度

- **以「使用者透過產品拿得到什麼」為準，不拿「repo 是公開的」當理由。** 例如 system prompt、工具名稱、內部錯誤訊息都不該從畫面或 API 回應被看到。
  理由：原始碼公開是開發上的選擇；產品照樣不該把內部設定交給使用者，這跟有沒有人能去讀原始碼無關。

## Git 與 PR

- **不直接推 `master`**：開分支 → PR → CI 綠燈、有人看過 diff 才合併（只允許 squash merge）。AI 不自己合併、不開 auto-merge。
  理由：PR 是改動的審查紀錄；CI 是強制關卡，不是可以跳過的建議。
- **先有 issue 才有 PR，小修正也一樣**：`gh issue create --project talkcal`（GitHub Project「talkcal」），開完把 Status 設好。
  理由：所有工作都在同一個看板上，交付指標（從開 issue 到合併）也才量得出來。
- **PR 描述第一行 `Closes #N`**，不用 `Refs #N`。理由：只有 Closes／Fixes／Resolves 會連結 issue，`.github/workflows/pr-issue.yml` 沒連到就紅燈。
- **commit 訊息開頭 `[類型]`**，只用：`[feat]` 新功能、`[fix]` 修問題、`[test]` 只動測試、`[docs]` 只動文件、
  `[refactor]` 重構（行為不變）、`[ci]` CI／部署／基礎設施、`[chore]` 其他（依賴、設定）。例：`[fix] .gitignore 的 out/ 錨定在根目錄`。
  - 一個 commit 只做一種類型：做功能途中修到 bug，bug 自己一個 `[fix]`、功能另一個 `[feat]`（反例：#103 的 2665d7a 把 auth 功能和 access log 的修正混在一起）。
  - 例外：驗證這次改動的測試跟改動放同一個 commit。拆開的話，改動那個 commit 單獨看沒有測試保護。
  - 理由：看歷史或 commit 脈絡圖時一眼分得出類型，不必從內容猜；要回退某個修正也只動那一個 commit。
- **commit 和 PR 不加 AI 工具的署名**（`Co-Authored-By: <AI>`、「Generated with …」這類字樣）。
  理由：改動由送出的人負責，AI 是工具；有沒有人看過，看的是 PR 的審查紀錄，不是署名。
- **改到 `infra/`、`deploy/`、`ops/` 或 `web/nginx.conf`，要核對 [docs/architecture/index.html](docs/architecture/index.html)**：
  元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對；要改就放同一個 PR，並把頁尾「最後核對」改成當天。
  理由：架構圖是給人看的證據，跟程式碼對不上比沒有圖更糟。
- **另開工作資料夾（git worktree）開在專案同層**（例如 `../talkcal-<issue 編號>`），不要開在 repo 裡面。
  移除前先看 `git status --ignored`，確認沒有原始碼被 ignore。PR 合併後刪掉資料夾和本機分支。
  理由：開在裡面會變成主資料夾裡沒追蹤的資料夾、干擾別人；被 ignore 的檔案移除時直接刪掉、不會警告（#103 因為 `.gitignore` 的 `out/` 掉過 5 個檔案）。
