@AGENTS.md

## Claude Code

上面那行把 [AGENTS.md](AGENTS.md) 整份引入：專案規則只寫在 AGENTS.md，這裡只放 Claude Code 才有的東西。
理由：repo 同時有 CLAUDE.md 和 AGENTS.md 時，Claude Code 預設只讀 CLAUDE.md，所以要用 `@AGENTS.md` 引入；
不用 symlink，因為 Windows 上 Git 預設把 symlink checkout 成一行文字，這份規則就整份不見了。

- 開 PR 之前先做一輪獨立審查，審這個分支相對 `master` 的 diff：
  - 每次都跑 `/code-review`（找 bug；文件寫錯路徑、說錯行為也算）。改到程式碼（含腳本、workflow）時，
    先跑 `/simplify`（找重複、可以簡化的地方，會直接改）再跑 `/code-review`，`/code-review` 才審得到最後進 PR 的那一版。
  - 找到的問題在同一個分支修掉；不修的，在 PR 裡寫為什麼。
  - 結果寫成 PR 描述 `Evidence` 的一點：跑了哪個、審到哪個 commit、找到幾個、怎麼處理。之後 push 又改了程式碼就重跑，更新這一點。
  - 理由：寫的人重看自己的 diff，會沿用寫的時候的假設；先讓審查指令過一輪，人審的時候看得到已經抓過什麼、剩下什麼要自己判斷。
