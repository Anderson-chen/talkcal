# commit 規則

- commit 前先用 `git branch --show-current` 確認是這次工作的分支：同一個資料夾可能被別的 session 切走。不對的話停下來問使用者，不要直接切回去。名稱不是 `類型/issue編號-英文短名` 的話，先照 `branch.md` 改名再 commit。
- commit 和 PR 標題：`類型(範圍): 改完之後變成怎樣`，例如 `feat(alerting): 資料庫連不上、備份失敗都會寄信`。
  - 類型：`feat`、`fix`、`test`（只動測試）、`docs`、`refactor`、`ci`、`chore`、`agents`；範圍用英文小寫（`auth`、`deploy`…），跨好幾塊就省略。
  - `agents`：改 `AGENTS.md`、`CLAUDE.md`、`.agents/rules/`。這些是 AI 工具開工前讀的規則，改了 AI 做事的方式就會變；`docs` 看起來只是說明，`chore` 看起來像雜事，都看不出這點。這個類型不在 Conventional Commits 裡，repo 沒有工具從類型推版號，加了不影響發版。
  - 寫改完的行為，不列做了什麼；不放 issue 編號。
- 一個 commit 只做一種類型（修 bug 和加功能分開）；驗證改動的測試跟改動放同一個 commit。
- commit 內文只寫為什麼（1～3 行），細節放 PR 描述。
- commit 訊息不要有 `Co-Authored-By: Claude`。
