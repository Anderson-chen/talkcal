# PR 規則

- PR 標題跟 commit 標題同一種寫法（見 `commit.md`）。
- PR 描述照 `.github/pull_request_template.md`：大標題用英文（`Summary`、`Dependencies`、`Evidence`、`Test Plan`、`Risk`），
  內文用中文，不適用的段落整段拿掉。演練時間軸這類很長的紀錄收進 `<details>`。
- PR 描述的截圖直接傳進 PR，不 commit 進分支：圖只有審查當下有用，一進 master 就永遠留在歷史裡，每次 clone 都要下載。
  - 用 `gh pr create` 或 `gh pr edit` 的 `--attach <檔案>`（gh 2.99.0 起才有）。描述裡先寫 `![說明](<本機路徑>)`，
    gh 上傳後會換成 GitHub 的網址；網頁上直接拖進描述也一樣。每張最多 10 MB。
  - 截圖先存在 scratchpad 這類 repo 外面的資料夾，不要放進 worktree，免得順手 commit 進去。
  - orphan 分支 `evidence` 是 gh 有 `--attach` 之前的做法，只留給舊 PR 的連結用，不再往裡面加圖、也不要刪或 force-push。
  - 文件（README、`docs/`）要引用的圖才進 master，放 `docs/images/`。
  - 演練紀錄（runbook、腳本會連過去的 .md）算文件，放 `docs/drills/`，它引用的圖一樣放 `docs/images/`。
- PR 描述不要有 `Generated with Claude Code`。
- PR 不貼 label：分類（`stage:`／`product:`、`p0`／`p1`、`area:`）都看它 `Closes` 的 issue，只維護一份。
  做的過程發現 issue 放錯段或少了 `area:`，回去改 issue 的 label。
- 開 PR 之前先做一輪獨立審查，審這個分支相對 `master` 的 diff：
  - 審多深看改到什麼，一個 PR 跨好幾級就照最高的那級：

    | 級 | 改到什麼 | 跑什麼 |
    |---|---|---|
    | 1 | 只有文件、規則、模板 | `/code-review medium` |
    | 2 | 程式碼（含腳本、workflow） | `/simplify` 再 `/code-review high` |
    | 3 | `infra/`、`deploy/`、`ops/`、`web/nginx.conf`、`backend/src/main/resources/db/migration/`，或後端的 `auth/` 模組、各模組的 `adapter/in/web/`（對外 API） | 第 2 級，再加 `/security-review`；出錯難收拾的（資料遷移、刪資源）由人手動跑 `/code-review ultra` |

    - `/code-review` 找 bug，文件寫錯路徑、說錯行為也算；等級要寫明，不寫會沿用上次打的等級。
    - `/simplify` 找重複、可以簡化的地方，會直接改，所以排在 `/code-review` 前面，`/code-review` 才審得到最後進 PR 的那一版。
    - `/code-review ultra` 是雲端多 agent 審查、另外計費，只能由人啟動；不自己另外組多個 agent 投票，同一個模型的錯誤會一起錯，多幾票不會比較準。
  - 找到的問題在同一個分支修掉；不修的，在 PR 裡寫為什麼。
  - 結果寫成 PR 描述 `Evidence` 的一點：屬於第幾級、跑了哪個、審到哪個 commit、找到幾個、怎麼處理。之後 push 又改了程式碼就重跑，更新這一點。
  - 理由：寫的人重看自己的 diff，會沿用寫的時候的假設；先讓審查指令過一輪，人審的時候看得到已經抓過什麼、剩下什麼要自己判斷。
    分級是因為出錯的代價不同：改一行文件跟改部署流程審得一樣深，前者浪費、後者不夠。
