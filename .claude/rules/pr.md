# PR 規則

- PR 標題跟 commit 標題同一種寫法（見 `commit.md`）。
- PR 描述照 `.github/pull_request_template.md`：大標題用英文（`Summary`、`Dependencies`、`Evidence`、`Test Plan`、`Risk`），
  內文用中文，不適用的段落整段拿掉。演練時間軸這類很長的紀錄收進 `<details>`。
- PR 描述不要有 `Generated with Claude Code`。
- PR 不貼 label：分類（`stage:`／`product:`、`p0`／`p1`、`area:`）都看它 `Closes` 的 issue，只維護一份。
  做的過程發現 issue 放錯段或少了 `area:`，回去改 issue 的 label。
- 開 PR 之前先做一輪獨立審查，審這個分支相對 `master` 的 diff：
  - 每次都跑 `/code-review`（找 bug；文件寫錯路徑、說錯行為也算）。改到程式碼（含腳本、workflow）時，
    先跑 `/simplify`（找重複、可以簡化的地方，會直接改）再跑 `/code-review`，`/code-review` 才審得到最後進 PR 的那一版。
  - 找到的問題在同一個分支修掉；不修的，在 PR 裡寫為什麼。
  - 結果寫成 PR 描述 `Evidence` 的一點：跑了哪個、審到哪個 commit、找到幾個、怎麼處理。之後 push 又改了程式碼就重跑，更新這一點。
  - 理由：寫的人重看自己的 diff，會沿用寫的時候的假設；先讓審查指令過一輪，人審的時候看得到已經抓過什麼、剩下什麼要自己判斷。
