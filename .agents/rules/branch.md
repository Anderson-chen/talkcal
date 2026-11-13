# 分支規則

- 開分支、開 worktree 前先問使用者，問的時候說要用的分支名稱。
- 分支名稱寫成 `類型/issue編號-英文短名`，例如 `fix/310-no-past-slots`：類型跟 commit 標題用同一組（見 `commit.md`），短名用英文小寫和 `-`。
  - 桌面 app 開 session 時會自己建 worktree 和分支，名稱是 `claude/<短名>-<亂碼>`，那時還沒讀到這份規則。動手前先看 `git branch --show-current`，名稱不合規則又還沒 push，就照第一條先問使用者，再用 `git branch -m <新名稱>` 改名。
  - 已經 push 的不改名：遠端會留下舊分支，upstream 也要重設，照原名開 PR。
  - 開別的 session 做一張 issue 時，brief 寫明分支名稱。
- worktree 開在 `.claude/worktrees/` 底下，從 `origin/master` 開。不在共用的資料夾 `git checkout`：會把正在那裡工作的 session 切走，它接下來的 commit 就落在你的分支上。
