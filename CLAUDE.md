- 回答語言：繁體中文
- commit 時千萬不要有Co-Authored-By: Claude 放在訊息
- commit 訊息開頭一律是 `[類型]`，類型用英文，例如 `[fix] .gitignore 的 out/ 錨定在根目錄`。只用這幾種：
  `[feat]` 新功能、`[fix]` 修問題、`[test]` 只加或改測試、`[docs]` 只改文件、`[refactor]` 重構（行為不變）、
  `[ci]` CI／部署／基礎設施、`[chore]` 其他雜項（依賴升級、設定）。
  一個 commit 只做一種類型：做功能途中發現 bug，修 bug 自己一個 `[fix]`、功能另一個 `[feat]`，不要混在一起
  （反例：#103 的 2665d7a 同時加了 auth 功能、又修了 access log 的兩個問題）。
  例外：驗證這次改動的測試跟改動放在同一個 commit，不另外拆成 `[test]` —— 拆開的話，改動那個 commit 單獨看是沒有測試保護的。
  `[test]` 只用在只動測試的時候（補測試、修測試）。
  理由：看 commit 歷史或 commit 脈絡圖時一眼分得出類型，不必從內容猜（用關鍵字猜會猜錯）；要回退某個修正時也只動那一個 commit。
- 開 PR 時描述裡不要有「🤖 Generated with Claude Code」這類註明是 Claude 寫的字樣
- 改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁尾的「最後核對」日期改成當天。
- 要開新的工作資料夾（例如 git worktree）時，開在專案的**同層**（例如 `C:/project/talkcal-<issue 編號>`），
  不要開在專案裡面（包括 `.claude/` 底下）：開在裡面會變成主資料夾 `git status` 裡沒追蹤的資料夾，干擾其他 session。
  移除 worktree 之前先看 `git status --ignored`，確認沒有原始碼被 ignore：被 ignore 的檔案移除時會直接刪掉、不會警告
  （#103 就因為 `.gitignore` 的 `out/` 而掉過 5 個檔案）。PR 合併後刪掉資料夾和本機分支。
- commit 脈絡圖由使用者自己呼叫 `/commit-map [分支]`（`.claude/skills/commit-map/`）產生，開 PR 時不會自動跑，
  也不要主動產生。原本的 PostToolUse hook 會被不相干的指令觸發、在 worktree 裡也產錯分支（#119），所以拿掉了。
- 開 PR 時加 label `ai-generated`，PR 範本的「AI 改動審查」段落原樣留著，**一格都不准勾、審查到的 commit 和審查紀錄都不准填**，
  之後 push、改描述時也不碰這段。那是使用者看完 diff 之後自己填的；AI review 檢查會先紅燈，這是預期的。
  理由：AI 和使用者用同一個 GitHub 帳號，檢查分不出是誰勾的，只能靠這條規則（見 `docs/ai-review.md`）。
