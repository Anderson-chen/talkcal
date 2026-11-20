---
name: commit
description: commit 時使用：commit 標題、內文怎麼寫，commit 前怎麼確認分支（開 PR 前的工作流程裡由修改者 `pr-author` 執行）。PR 標題的寫法也照這份。
---

# commit

- commit 前先用 `git branch --show-current` 確認是這次工作的分支：同一個資料夾可能被別的 session 切走。不對的話停下來問使用者，不要直接切回去。名稱不合 [branch skill](../branch/SKILL.md) 的寫法（走流程是 `類型/issue編號-英文短名`，沒走流程是 `類型/英文短名`）、又還沒 push 的話，先照 branch skill 改名再 commit；印出空白是沒有分支（detached HEAD），照 branch skill 開分支。
- commit 和 PR 標題：`類型(範圍): 改完之後變成怎樣`，例如 `feat(alerting): 資料庫連不上、備份失敗都會寄信`。
  - 類型：`feat`、`fix`、`test`（只動測試）、`docs`、`refactor`、`ci`、`chore`、`agents`；範圍用英文小寫（`auth`、`deploy`…），跨好幾塊就省略。
  - `agents`：改 `CLAUDE.md`、`.claude/rules/`、`.claude/skills/`、`.claude/agents/`。這些是 AI 工具開工前讀的規則和審查員的指示，改了 AI 做事的方式就會變；`docs` 看起來只是說明，`chore` 看起來像雜事，都看不出這點。這個類型不在 Conventional Commits 裡，repo 沒有工具從類型推版號，加了不影響發版。
  - 寫改完的行為，不列做了什麼；不放 issue 編號。
- 一個 commit 只做一種類型（修 bug 和加功能分開）；驗證改動的測試跟改動放同一個 commit。
- commit 內文只寫為什麼（1～3 行），細節放 PR 描述。
- commit 訊息不要有 `Co-Authored-By: Claude`。

## 下一步

沒走流程時（使用者沒打 `/issue`、`/open-pr`），主 session 直接改，不開 issue，也不呼叫修改者、審核者、投票者。commit 之後：

1. 照 [open-pr skill](../open-pr/SKILL.md) 的「照改到的地方驗證」執行測試。
2. 照 `.claude/rules/commands.md` 先問使用者再 push。
3. 開 PR，描述照 `open-pr` 的「PR 描述」。沒有 issue 可以連，不寫 `Closes`，也沒有審核、投票的回報可以附。

走流程時，修改者 commit 之後照 [review-levels skill](../review-levels/SKILL.md) 的分級表執行列給修改者的指令，再回報；主 session 接著照 `open-pr` 呼叫審核者。
