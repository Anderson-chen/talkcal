# CLAUDE.md

專案介紹、使用方式，看 [README.md](README.md)。

## 語言

回答用繁體中文。

## 工作流程

使用者打 `/issue` 或 `/open-pr` 時才走工作流程，順序和每一步執行的 skill 見 [.claude/skills/README.md](.claude/skills/README.md)。`.claude/rules/` 放每一步都要遵守的規則，Claude Code 在 session 開始時整個資料夾載入，frontmatter 有 `paths:` 的只在讀寫到那些路徑時載入。寫文件、註解、介面文字、prompt、issue、PR、commit 的文字，都照 `.claude/rules/writing.md`；要執行指令時照 `.claude/rules/commands.md`。

### 沒走流程的改動

使用者沒打 `/issue`、`/open-pr` 時，主 session（使用者正在對話的那個 session）直接改，不開 issue，也不呼叫修改者、審核者、投票者：

1. 分支照 `.claude/skills/branch/SKILL.md` 的命名，沒有 issue 編號，寫成 `類型/英文短名`。
2. 照 `commit` skill commit。
3. 照 `.claude/skills/open-pr/SKILL.md` 的「照改到的地方驗證」執行測試。
4. 照 `.claude/rules/commands.md` 先問使用者再 push，然後開 PR。描述照 `open-pr` 的寫法，沒有 issue 可以連，不寫 `Closes`，也沒有審核、投票的回報可以附。
