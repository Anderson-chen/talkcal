@AGENTS.md
@.agents/rules/branch.md
@.agents/rules/comments.md
@.agents/rules/commit.md
@.agents/rules/issue.md
@.agents/rules/pr.md
@.agents/rules/writing.md

上面幾行把 [AGENTS.md](AGENTS.md) 和 `.agents/rules/` 的規則整份引入：專案有 CLAUDE.md 時，Claude Code 不會自己讀 AGENTS.md，規則資料夾也只自動載入 `.claude/rules/`，所以都要 `@` 引入；
不用 symlink，因為 Windows 上 Git 預設把 symlink checkout 成一行文字，規則就整份不見了。
