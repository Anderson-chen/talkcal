- 回答語言：繁體中文
- commit 時千萬不要有Co-Authored-By: Claude 放在訊息
- commit 訊息開頭一律是 `[類型]`，類型用英文，例如 `[fix] .gitignore 的 out/ 錨定在根目錄`。只用這幾種：
  `[feat]` 新功能、`[fix]` 修問題、`[test]` 只加或改測試、`[docs]` 只改文件、`[refactor]` 重構（行為不變）、
  `[ci]` CI／部署／基礎設施、`[chore]` 其他雜項（依賴升級、設定）。混了好幾種就標最主要的那個，修問題優先標 `[fix]`。
  理由：看 commit 歷史或 commit 脈絡圖時一眼分得出類型，不必從內容猜（用關鍵字猜會猜錯）。
- 開 PR 時描述裡不要有「🤖 Generated with Claude Code」這類註明是 Claude 寫的字樣
- 改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁尾的「最後核對」日期改成當天。
- 要開新的工作資料夾（例如 git worktree）時，開在專案的**同層**（例如 `C:/project/talkcal-<issue 編號>`），
  不要開在專案裡面（包括 `.claude/` 底下）：開在裡面會變成主資料夾 `git status` 裡沒追蹤的資料夾，干擾其他 session。
  移除 worktree 之前先看 `git status --ignored`，確認沒有原始碼被 ignore：被 ignore 的檔案移除時會直接刪掉、不會警告
  （#103 就因為 `.gitignore` 的 `out/` 而掉過 5 個檔案）。PR 合併後刪掉資料夾和本機分支。
