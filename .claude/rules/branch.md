# 分支規則

- 要換到別的分支做事時，開一個自己的 git worktree，不在共用的資料夾（clone 下來的那一份）裡 `git checkout`。
  - 理由：一個資料夾同一時間只能在一個分支上。可能有別的 session 或工具正在同一個資料夾工作，你一切走，它接下來的編輯和 commit 就落在你的分支上，git 不會有任何警告；切的人也不一定知道有人在用。
  - 開：先 `git fetch`，再 `git worktree add .claude/worktrees/<名稱> -b <分支名> origin/master`；Claude Code 用 `EnterWorktree`，一樣開在 `.claude/worktrees/` 底下、從 `origin/master` 開。
  - 做完：`git worktree remove <路徑>`。
  - 不要開在 scratchpad 這類很深的暫存資料夾：資料夾路徑加上 repo 裡最深的檔案超過 Windows 預設的 260 字元，checkout 就會失敗（`Filename too long`）。
- 發現 commit 落在別人的分支：不要在共用的資料夾切回自己的分支，那樣又會切走對方。
  在自己的 worktree 裡 `git cherry-pick` 那個 commit，再請對方（或確認對方沒在用之後）把錯放的那個從它的分支拿掉。
