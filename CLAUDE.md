@AGENTS.md

上面那行把 [AGENTS.md](AGENTS.md) 整份引入：Claude Code 不會自己讀 AGENTS.md，所以要 `@` 引入；
不用 symlink，因為 Windows 上 Git 預設把 symlink checkout 成一行文字，規則就整份不見了。
