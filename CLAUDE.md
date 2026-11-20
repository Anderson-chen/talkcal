# CLAUDE.md

專案介紹、使用方式，看 [README.md](README.md)。

## 語言

回答用繁體中文。

## 工作流程

使用者打 `/issue`（從開 issue 開始）或 `/open-pr`（issue 和分支已經有了）時，照下表的順序做，每一步執行那一列的 skill。`issue`、`branch`、`open-pr` 設了 `disable-model-invocation: true`，只有使用者打指令才會執行。skill 放在 `.claude/skills/`，做到那一步才載入全文；`.claude/rules/` 放每一步都要遵守的規則，Claude Code 在 session 開始時整個資料夾載入，frontmatter 有 `paths:` 的只在讀寫到那些路徑時載入。主 session 指使用者正在對話的那個 session。

| 步驟 | 誰執行 | 做什麼 | 執行的 skill |
|---|---|---|---|
| 1 | 主 session | 開 issue、改 issue：先進 plan mode 問清楚，分支名稱要用到 issue 編號 | `issue` |
| 2 | 主 session | 開分支；分支已經建好（例如桌面 app 開 session 時建的）也要看名稱合不合規則 | `branch` |
| 3 | 主 session | 依序呼叫下面的 3-1 到 3-3，兩票通過後執行測試、開或改 PR | `open-pr` |
| 3-1 | 修改者（`pr-author`） | 修改、commit：改程式碼、寫註解照 `comments.md`，改到基礎設施另見 `.claude/rules/infra.md` | `commit`、`open-pr`（預先載入） |
| 3-2 | 審核者（`pr-reviewer`） | 審核：列出問題；有列就回到 3-1 | `open-pr`（預先載入） |
| 3-3 | 三個投票者（`pr-voter-fable`、`pr-voter-opus`、`pr-voter-sonnet`） | 投票：各投一票；兩票通過才執行測試、開 PR，沒過就回到 3-1 | `vote`（預先載入） |

括號裡是 `.claude/agents/` 的 subagent 名稱；「預先載入」指定義檔的 `skills:`。每一步寫文件、註解、介面文字、prompt、issue、PR、commit 的文字，都照 `.claude/rules/writing.md`；要執行指令時照 `.claude/rules/commands.md`。

### 沒走流程的改動

使用者沒打 `/issue`、`/open-pr` 時，主 session 直接改，不開 issue，也不呼叫修改者、審核者、投票者：

1. 分支照 `.claude/skills/branch/SKILL.md` 的命名，沒有 issue 編號，寫成 `類型/英文短名`。
2. 照 `commit` skill commit。
3. 照 `.claude/skills/open-pr/SKILL.md` 的「照改到的地方驗證」執行測試。
4. 照 `.claude/rules/commands.md` 先問使用者再 push，然後開 PR。描述照 `open-pr` 的寫法，沒有 issue 可以連，不寫 `Closes`，也沒有審核、投票的回報可以附。

### 新增一步

新增一步時，skill 放進 `.claude/skills/<名稱>/SKILL.md`，最後一段寫下一步執行哪個 skill，並在這張表加一行。每一步都要遵守的規則才放進 `.claude/rules/`；只跟特定檔案有關的，用 `paths:` 限定路徑。

