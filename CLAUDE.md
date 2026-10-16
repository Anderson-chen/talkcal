@AGENTS.md

## Claude Code

上面那行把 [AGENTS.md](AGENTS.md) 整份引入：專案規則只寫在 AGENTS.md，這裡只放 Claude Code 才有的東西。
理由：repo 同時有 CLAUDE.md 和 AGENTS.md 時，Claude Code 預設只讀 CLAUDE.md，所以要用 `@AGENTS.md` 引入；
不用 symlink，因為 Windows 上 Git 預設把 symlink checkout 成一行文字，這份規則就整份不見了。
