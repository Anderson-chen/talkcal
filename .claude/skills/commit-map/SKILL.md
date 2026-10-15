---
name: commit-map
description: 產生某個分支的 commit 脈絡圖（每個 commit 的原因、改了哪些檔、diff 和檔案用途），輸出成 HTML 傳給使用者。只在使用者呼叫 /commit-map 時用。
argument-hint: "[分支，預設目前分支] [基準，預設 origin/master]"
disable-model-invocation: true
---

# commit 脈絡圖

把一個分支相對基準多出來的 commit 畫成一頁 HTML：第一頁是總覽（每個 commit 改了哪幾塊），
之後每個 commit 一個分頁，列出它的訊息、改到的檔案、diff、那個版本的完整檔案和檔案開頭註解。
內容全部來自 git，所以隨時重跑都是最新的。

使用者給的參數：`$ARGUMENTS`

## 步驟

1. 先 `git fetch --prune`，用遠端最新的狀態畫。
2. 決定要畫哪個分支：
   - 有給分支名稱就用它。本機沒有這條分支、但遠端有，就用 `origin/<分支>`
   - 沒給就用目前的分支；目前在 `master` 上的話，問使用者要畫哪一條（畫 master 對 origin/master 是空的）
   - 給的是 PR 編號（例如 `#114`）：用 `gh pr view <編號> --json headRefName` 查出分支
3. 在 repo 根目錄跑：

   ```bash
   python -I .claude/skills/commit-map/commit_map.py <分支> [基準]
   ```

   最後一行是輸出的檔案路徑（`build/commit-map/<分支>.html`，`build/` 被 gitignore，不進 git）。
4. 用 SendUserFile 把那個檔案傳給使用者（display 用 render）。

## 注意

- 分支已經 squash 合併、又刪掉的話就畫不出原本的 commit 了：master 上只剩一個 commit。要留就在合併前畫
- 要對 worktree 裡的分支畫，在主資料夾跑、給分支名稱就好（同一個 repo 看得到所有本機分支）
- 畫出來是空的（0 個 commit）：多半是基準已經包含這條分支，例如已經合併了；換一個基準（合併前的 master commit）再畫
