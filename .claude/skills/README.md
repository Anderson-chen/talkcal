# skills

一張 issue 從開 issue 到開 PR 的工作流程。使用者打 `/issue`（從開 issue 開始）或 `/open-pr`（issue 和分支已經有了）時，照下表的順序做，每一步執行那一列的 skill。主 session 指使用者正在對話的那個 session。

| 步驟 | 誰執行 | 做什麼 | 執行的 skill |
|---|---|---|---|
| 1 | 主 session | 開 issue、改 issue：先進 plan mode 問清楚，分支名稱要用到 issue 編號 | `issue` |
| 2 | 主 session | 開分支；分支已經建好（例如桌面 app 開 session 時建的）也要看名稱合不合規則 | `branch` |
| 3 | 主 session | 依序呼叫下面的 3-1 到 3-3，兩票通過後執行測試、開或改 PR | `open-pr` |
| 3-1 | 修改者（`pr-author`） | 修改、commit：改程式碼、寫註解照 `comments.md`，改到基礎設施另見 `.claude/rules/infra.md` | `commit`、`review-levels`（預先載入） |
| 3-2 | 審核者（`pr-reviewer`） | 審核：列出問題；有列就回到 3-1 | `review-levels`（預先載入） |
| 3-3 | 三個投票者（`pr-voter-fable`、`pr-voter-opus`、`pr-voter-sonnet`） | 投票：各投一票；兩票通過才執行測試、開 PR，沒過就回到 3-1 | `vote`（預先載入） |

括號裡是 `.claude/agents/` 的 subagent 名稱；「預先載入」指定義檔的 `skills:`。

`issue`、`branch`、`open-pr` 設了 `disable-model-invocation: true`，只有使用者打指令才會執行。設了這個的 skill，定義檔的 `skills:` 也預先載入不了，所以 agent 要用的內容放在沒設的 skill：`commit`、`review-levels`、`vote`。

## 新增一步

skill 放進 `.claude/skills/<名稱>/SKILL.md`，最後一段寫下一步執行哪個 skill，並在這張表加一行。agent 要預先載入的 skill 不設 `disable-model-invocation`。每一步都要遵守的規則才放進 `.claude/rules/`；只跟特定檔案有關的，用 `paths:` 限定路徑。
