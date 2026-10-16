@AGENTS.md

## Claude Code

上面那行把 [AGENTS.md](AGENTS.md) 整份引入：專案規則只寫在 AGENTS.md，這裡只放 Claude Code 才有的東西。
理由：repo 同時有 CLAUDE.md 和 AGENTS.md 時，Claude Code 預設只讀 CLAUDE.md，所以要用 `@AGENTS.md` 引入；
不用 symlink，因為 Windows 上 Git 預設把 symlink checkout 成一行文字，這份規則就整份不見了。

- commit 脈絡圖由使用者自己呼叫 `/commit-map [分支]`（`.claude/skills/commit-map/`）產生，開 PR 時不會自動跑，
  也不要主動產生。原本的 PostToolUse hook 會被不相干的指令觸發、在 worktree 裡也產錯分支（#119），所以拿掉了。
