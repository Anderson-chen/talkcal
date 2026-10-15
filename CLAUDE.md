- 回答語言：繁體中文
- commit 時千萬不要有Co-Authored-By: Claude 放在訊息
- 開 PR 時描述裡不要有「🤖 Generated with Claude Code」這類註明是 Claude 寫的字樣
- 改到 `infra/`、`deploy/`、`ops/` 底下的檔案或 `web/nginx.conf` 時，都要檢查 `docs/architecture/index.html`：
  圖上的元件、依賴、參數值、檔案行號（`F`、`L` 兩張表）還對不對。要改就跟程式碼放在同一個 PR，
  並把頁尾的「最後核對」日期改成當天。
- 要開新的工作資料夾（例如 git worktree）時，開在專案的**同層**（例如 `C:/project/talkcal-<issue 編號>`），
  不要開在專案裡面（包括 `.claude/` 底下）：開在裡面會變成主資料夾 `git status` 裡沒追蹤的資料夾，干擾其他 session。
  移除 worktree 之前先看 `git status --ignored`，確認沒有原始碼被 ignore：被 ignore 的檔案移除時會直接刪掉、不會警告
  （#103 就因為 `.gitignore` 的 `out/` 而掉過 5 個檔案）。PR 合併後刪掉資料夾和本機分支。
