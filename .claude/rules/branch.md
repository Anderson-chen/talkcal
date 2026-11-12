# 分支規則

- 同一個 repo 有別的 session 或工具同時在改時，各自開一個 git worktree，不在同一個資料夾裡切分支。
  - 理由：一個資料夾同一時間只能在一個分支上。一邊 `git checkout` 切走，另一邊接下來的編輯和 commit 就落在別人的分支上，git 不會有任何警告。
  - 開：`git worktree add <路徑> -b <分支名> master`；Claude Code 用 `EnterWorktree`，會開在 `.claude/worktrees/` 底下。
  - 做完：`git worktree remove <路徑>`。
  - 不要開在 scratchpad 這類很深的暫存資料夾：repo 裡最長的檔案路徑已經 140 多字元，加起來超過 Windows 預設的 260 字元，checkout 會失敗（`Filename too long`）。
